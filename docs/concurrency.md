# Concurrency design of the matching engine

The decision itself is [ADR 0005](adr/0005-single-writer-per-book-not-a-lock-free-order-book.md).
This document is the engineering detail behind it, written for someone who already knows
concurrency and wants to know what is different in Java.

---

## 1. The one-paragraph version

Every order and cancel carries its **symbol as the Kafka message key**, so all messages for one
book land in one partition, and a partition is consumed by exactly one thread in a consumer
group. A book therefore has exactly one writer, and the matching path contains no lock, no
atomic and no CAS. Readers — the depth endpoint, the metrics scrape — never touch the book: after
each consumed batch the writer publishes an **immutable `BookSnapshot`** through a `volatile`
field, and readers load that reference. Parallelism is across symbols; within a symbol everything
is strictly sequential, which is what price-time priority means.

---

## 2. Thread inventory

| Thread | Count | Touches | Synchronisation |
|---|---|---|---|
| Kafka consumer (writer) | `oms.engine.concurrency`, ≤ partitions | Its own books, mutably | none |
| HTTP request (reader) | Tomcat pool | `BookSlot.snapshot` only | one volatile read |
| Metrics scrape (reader) | Micrometer scheduler | `BookSlot.snapshot` only | one volatile read |
| Kafka producer I/O | 1 per producer | its own record accumulator | Kafka's own |

There is no thread in this table that waits for another thread in this table.

---

## 3. What makes the single-writer guarantee hold

It is a chain, and every link is load-bearing:

```
DomainEvent.partitionKey() returns the symbol      ← the key is a property of the event type,
                                                     not an argument at the producer call site
  → the producer cannot forget it
    → all messages for a symbol hash to one partition
      → Kafka assigns a partition to at most one consumer in a group
        → Spring runs one thread per assigned partition
          → one writer per book
```

Two details that are easy to get wrong and would silently break it:

**Accepted orders and cancel requests are consumed in *one* consumer group.** Two groups would
each get their own partition assignment, so a cancel for `HBL` could be handled by a thread that
does not own the `HBL` book. Same group, same key, same assignment, same thread.

**The producer key is never null and never derived at the call site.** `partitionKey()` is a
method on the event record. A null key would round-robin the record across partitions and destroy
both the single-writer property and the ordering.

The ordering argument matters beyond thread safety: keying by symbol is also what stops a cancel
from overtaking the order it cancels. Different keys mean different partitions mean no ordering
guarantee between them.

---

## 4. The reader side, in Java Memory Model terms

```java
// BookSlot
private volatile BookSnapshot snapshot;              // the only synchronisation in the engine

public void publishSnapshot() {                      // writer thread only
    this.snapshot = book.snapshot(snapshotDepth);     // release
}

public BookSnapshot currentSnapshot() {               // any thread
    return snapshot;                                  // acquire
}
```

A volatile write is a **release**; a volatile read is an **acquire**. Everything the writer did
before the write — allocating the snapshot, filling its lists, copying them with `List.copyOf` —
*happens-before* anything a reader does after the read. A reader that observes the new reference
is guaranteed to observe a fully constructed object.

This is **safe publication**, and it is worth being precise about why the keyword is not
optional. Without `volatile`, the risk is not staleness. A data race on a reference in the JMM
permits a reader to see the reference *before* the object's fields are visible — the same class of
bug as publishing a pointer with no release fence, and the reason the double-checked-locking
idiom was broken in Java before 1.5.

Immutability has to be real for this to work. `BookSnapshot.of` wraps both level lists with
`List.copyOf`. A record holding a mutable list is only *shallowly* immutable, and the writer
would still be holding a reference to a list a reader is iterating.

### Mapping to C++

| Java | C++ |
|---|---|
| `volatile BookSnapshot snapshot` | `std::atomic<std::shared_ptr<const Snapshot>>` |
| volatile write | `store(p, std::memory_order_release)` |
| volatile read | `load(std::memory_order_acquire)` |
| old snapshot collected | hazard pointers / epochs / RCU / refcount |

Three differences that actually change the engineering:

1. **Reclamation disappears.** The hard part of a C++ read-mostly structure is knowing when the
   old version can be freed. Here the collector answers it and the read path is a single load —
   no refcount traffic, no epoch bookkeeping. This is the one place the managed runtime is
   unambiguously ahead.
2. **There is no relaxed ordering on a field.** Every Java volatile access is at least
   acquire/release and sequentially consistent with respect to other volatile accesses. When you
   genuinely want weaker ordering the tool is `VarHandle` — `getAcquire`, `setRelease`,
   `getOpaque`, `compareAndExchangeRelease`. It is the right instrument and it is unnecessary
   here, because this publication happens once per *batch*, not once per order.
3. **Layout is not yours.** No `alignas`, no guaranteed field order, and padding fields can be
   reordered or removed. See §6.

---

## 5. Allocation and GC pressure

This is the part of the design that has no C++ analogue, and the part most likely to be
underestimated by someone coming from C++.

**Allocation in Java is cheap; collection is not, and you do not choose when it happens.** A
pointer bump in the thread-local allocation buffer costs a few instructions. The bill arrives
later, on a thread you did not schedule, as a pause. A book that allocates per fill does not show
up as slower matching — it shows up as a worse p99 in something else entirely, which is why it is
so hard to attribute after the fact.

So the matching path allocates **nothing**:

| Technique | What it avoids |
|---|---|
| `MatchListener` takes primitives instead of returning `List<Fill>` | one list + one object per fill, all garbage immediately |
| `OrderNode` free list (`pooledNodeCount()` proves it works) | one object per resting order, per quote/pull cycle |
| `long` ticks, never `BigDecimal` (ADR 0002) | a heap object per price comparison |
| `firstEntry()` + direct `remove`, no `Iterator` | an escaping iterator object per level walked |
| no streams, no lambdas in the loop | a pipeline object per stage, plus boxing |
| snapshot built per **batch**, not per order | the only deliberate allocation, amortised over ~200 messages |

What is left in the steady state: one `NewOrder` per incoming message — already paid for by the
Kafka deserialiser — a `PriceLevel` the first time a price is touched, and one `BookSnapshot` per
batch.

The JMH GC profiler measures this rather than asserting it: `gc.alloc.rate.norm` is bytes
allocated per operation, and the numbers are in [performance.md](performance.md).

### The pool is bounded, on purpose

An unbounded free list is a memory leak with a respectable name. `OrderNode.clear()` also nulls
the `UUID` and `String` references before a node returns to the pool — a pooled object that
retains references keeps those objects alive for as long as the pool does, which is a leak that
looks like a cache.

---

## 6. False sharing, and why there is no padding in this code

False sharing happens when two cores write different variables that share a 64-byte cache line:
each write invalidates the other core's copy and the line ping-pongs between them. In C++ the fix
is `alignas(64)` or explicit padding.

Java gives you no layout control. Padding fields can be reordered or elided by the JVM, so
hand-rolled padding is unreliable. The supported tool is
`@jdk.internal.vm.annotation.Contended`, which requires `-XX:-RestrictContended` to apply to
application classes.

**This project does not use it, and that is a considered decision, not an oversight.** Look for
the line two cores would fight over:

- The book's internal state — tree nodes, `PriceLevel` totals, `OrderNode` fields — is written by
  exactly **one** thread. One writer cannot false-share with itself.
- There is no shared counter in the matching path. The sequence number is a plain `long` field on
  the book, written by its own writer.
- Micrometer counters are touched once per batch, not once per order.
- The `volatile` snapshot field in each `BookSlot` *is* written by a writer and read by readers —
  but once per batch, so the invalidation traffic is a few hundred times per second, not millions.

The one place it could matter: several `BookSlot` objects allocated close together, on different
partitions handled by different threads, publishing snapshots frequently — their volatile fields
could share a line. That is a hypothesis to **measure** (`perf c2c`, or a JMH experiment with and
without `@Contended`) before acting on. Padding added on the strength of an argument rather than a
measurement is cargo cult, and reviewers who know the subject can tell the difference.

---

## 7. Batching, and what it is actually for

The Kafka listeners are **batch** listeners (`List<ConsumerRecord<...>>`). Three reasons, in
order of how much they matter:

1. **The snapshot is published once per batch.** It is the only deliberate allocation in the
   book; per-order publication would put it back on the per-order cost.
2. **Kafka sends are awaited once per batch.** One broker round trip for a batch of 200 records
   instead of 200 round trips.
3. Less per-record framework overhead in the poll loop.

What batching does **not** change is ordering: records arrive in the batch in offset order and are
processed in that order. That is the property the entire design exists to protect.

`MAX_POLL_RECORDS = 200` is a deliberate middle. Larger batches amortise better; they also
lengthen the gap between offset commits, which is exactly how much replay a crash costs.

---

## 8. Durability: why offsets are committed after the broker acknowledges

The engine has no database. Its whole durability story is "the books can be rebuilt by replaying
the partition". That only works if the offset never advances past a batch whose trades did not
reach the broker — otherwise a rebuild cannot reproduce that fill, because the input record is
behind the committed offset too.

So the order of operations in the batch handler is:

```
match every record  →  await every send  →  publish snapshots  →  return  →  Spring commits offset
```

`EventPublisher.BatchSink.awaitAll()` blocks on `CompletableFuture.allOf(...)`. A failure throws,
the batch is retried, and — because trade ids are deterministic — the retry re-emits identical
trade ids that downstream deduplicates.

**Blocking retry, never `@RetryableTopic`.** Non-blocking retry republishes a failed record to a
retry topic and carries on, which means a retried order is matched *after* orders that arrived
later. Price-time priority would be violated. Head-of-line blocking is not the lesser evil here;
it is the only acceptable behaviour, and the backoff budget bounds it.

---

## 9. Recovery, and the one class that makes it safe

A restart, or a consumer-group rebalance moving a partition to another instance, loses the
in-memory book. It is rebuilt by consuming the partition from the earliest offset
(`auto-offset-reset=earliest`), which re-executes the same matches and **re-emits the same
trades**.

That is safe only because of `TradeIds`:

```java
public static UUID of(String symbol, long sequence) {
    return UUID.nameUUIDFromBytes((symbol + '\u0000' + sequence).getBytes(UTF_8));
}
```

The trade id is a function of `(symbol, sequence)`, and the sequence is a deterministic function
of the input stream, so a replay produces byte-identical ids. order-service deduplicates fills on
the primary key `(trade_id, order_id)`, so re-emission is a no-op.

With `UUID.randomUUID()`, every re-emitted trade would look new, order-service would apply it
again, and **every position in the platform would double**. The engine would appear correct and
the money would be wrong — the worst possible failure mode. Recovery design and id generation are
one decision, not two.

Known limitation: replay time grows with topic retention. At 7 days, a cold start replays up to 7
days of orders for its partitions. The production answer is periodic book snapshots to a compacted
topic so a rebuild starts from the last snapshot and replays only the tail. Not built; recorded in
[performance.md](performance.md) as the next piece of work.

---

## 10. What is tested, and what testing can and cannot prove

| Test | Proves |
|---|---|
| `SnapshotPublicationConcurrencyTest` | One writer at full speed plus four readers for 1.5s: no torn snapshot, no crossed book, no blocking. Asserts self-consistency on every read — bids descending, asks ascending, best price agreeing with the first level. |
| `snapshotListsAreUnmodifiable` | A reader cannot mutate what the writer published. |
| `MatchingEngineTest.slotCreationIsRaceFree` | Eight threads first-touching one symbol create exactly one book. |
| `BookInvariantTest` | 20,000 random operations × 4 seeds: the book never crosses, and `submitted = 2 × traded + cancelled + resting` holds exactly. |
| `TradeIdsTest` | Determinism, and that a replayed stream reproduces identical ids. |

**A test cannot prove the absence of a data race.** A race is a property of the memory model, not
of an execution: a broken publication can pass a million runs on x86, where stores are already
ordered, and fail on ARM. The correctness argument is the JMM one in §4; the test is the empirical
companion to it.

The same division of labour as reasoning about `memory_order_acquire`/`release` and then running
ThreadSanitizer. Java's equivalent of TSan for this purpose is **jcstress**, which explores
interleavings and memory-model outcomes exhaustively for small cases. A jcstress harness for the
publication is the right next step for a serious claim, and it is not written yet — stated here
rather than left implied by a passing test.

---

## 11. Summary for an interview

Five sentences, in the order they should be said:

1. All orders and cancels for a symbol carry that symbol as the Kafka key, so one partition means
   one consumer thread means one writer per book — no lock in the matching path at all.
2. Readers never touch the book: the writer publishes an immutable snapshot through a volatile
   field, which is safe publication, so a depth query cannot block matching or see a torn book.
3. I deliberately did not build a lock-free book, because concurrent mutation of one book would
   destroy the price-time ordering that is the book's entire purpose — the concurrency win here is
   a partitioning decision, not a clever lock.
4. The matching path allocates nothing — fills are pushed as primitives, nodes come from a bounded
   free list, prices are `long` ticks — because in Java the cost of allocation is a GC pause you
   do not schedule, and it lands in the p99 of something else.
5. Books are in memory, so recovery is a partition replay, which is only safe because trade ids
   are derived from `(symbol, sequence)` rather than random — with random ids a rebuild would
   double every position in the platform.
