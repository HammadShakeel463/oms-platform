# ADR 0005 — Single-writer-per-book via Kafka partitioning, not a lock-free order book

- **Status:** Accepted
- **Date:** 2026-09-29
- **Phase:** 3

## Context

The matching engine holds order books in memory and is the only latency-sensitive component in
the platform. Two things need concurrent access to a book:

1. **Writers** — orders and cancels arriving from Kafka, which mutate the book.
2. **Readers** — the depth-of-book REST endpoint and the Prometheus metrics scrape, which only
   observe it.

The brief for this project asked for a "lock-free or carefully lock-minimised concurrent order
book". That framing deserves a direct answer rather than a compliant one, because the obvious
reading of it — several threads mutating one book through CAS loops — is the wrong design, and
being able to say why is worth more than having built it.

## Decision

**One writer thread per book, guaranteed by the message key. No lock in the matching path at
all. Readers consume an immutable snapshot published through a `volatile` reference.**

```
all orders and cancels for a symbol carry that symbol as the Kafka message key
  → they land in one partition
    → a partition is consumed by exactly one thread in a consumer group
      → a book has exactly one writer, for free
```

Concretely:

- `PriceTimeOrderBook` is **not thread safe and documents that it is not**. No
  `synchronized`, no `Lock`, no atomic field. It is a plain mutable data structure.
- `MatchingEngine` holds `ConcurrentHashMap<String, BookSlot>`. The map is concurrent because
  different writer threads create and look up entries for *different* symbols; everything
  inside a `BookSlot` is single-writer.
- After each consumed Kafka batch the writer builds an immutable `BookSnapshot` and assigns it
  to a `volatile` field in `BookSlot`. Readers read that field. Neither side ever waits for the
  other.
- Parallelism is **across symbols** — 12 partitions, up to 12 engine threads — and strictly
  serial within a symbol.

### Why the reader side is correct

A volatile write is a release; a volatile read is an acquire. Everything the writer did before
the volatile write — allocating the snapshot, filling its lists — *happens-before* anything a
reader does after the volatile read. A reader that sees the new reference therefore sees a fully
constructed object. This is safe publication, and it is the entire synchronisation mechanism.

Dropping `volatile` would not merely risk staleness. In the Java Memory Model a data race on a
reference permits a reader to observe the reference before the object's fields are visible — the
same class of bug as publishing a pointer without a release fence.

## Alternatives considered

### A genuinely lock-free book (CAS over the level structure)

Rejected, and this is the interesting rejection.

A lock-free book would buy the ability for several threads to mutate one book concurrently.
**That is a capability nobody wants.** Matching is inherently sequential: price-time priority is
*defined* by an order of arrival. Two threads matching the same book concurrently do not make it
faster — they make "who was first" ambiguous, which is the one property the book exists to
establish. You would be paying CAS retries, ABA hazards and memory-reclamation complexity to
destroy the guarantee you are being paid to provide.

The cost side is worse than it first looks. In C++ you would reach for a CAS loop over a price
ladder and manage reclamation with hazard pointers or epochs. In Java the reclamation problem
vanishes — the collector solves it — but the rest gets harder, not easier: no value types, so
every CAS is on a reference rather than on an inline struct; `compareAndExchange` on a
`VarHandle` rather than on a field you can see the layout of; and no control over object layout
at all, so the cache-line packing that makes a C++ lock-free structure fast is not expressible.

Against that, the single-writer design costs **nothing**: no atomic instruction, no cache-line
ping-pong between cores for the book's own state, no retry loop, and the code is a plain data
structure that a reviewer can read.

The honest summary: **the concurrency win here is a partitioning decision, not a clever lock.**
Every fast exchange matching core I know of is single-threaded per book for exactly this reason.
Lock-free structures belong where contention is unavoidable and ordering is not part of the
contract — a work queue, a free list, a metrics counter — not where sequencing *is* the product.

### `ReentrantReadWriteLock` around the book

Works, and is worse in every dimension. Readers contend with the writer, a burst of depth
queries can starve matching, and the writer takes a lock on every single order to guard against
a reader that arrives a few times a second. It also creates a way for an HTTP request to affect
matching latency, which is a property worth not having.

### `StampedLock` with optimistic reads

The closest competitor, and the most C++-flavoured answer: it is a sequence lock, exactly what
you would hand-roll with a version counter and two acquire/release fences. The writer bumps a
stamp, the reader reads the stamp, reads the data, re-reads the stamp and retries if it moved.
Readers almost never block the writer.

Rejected because it still requires the reader to walk **live mutable structures** and be
prepared to see them mid-update. Publishing an immutable snapshot removes the interaction
entirely: no retry, no torn read possible, and the reader walks a structure that by construction
nobody will modify. It is also much harder to get wrong — a `StampedLock` optimistic read that
forgets to validate is a silent corruption, and it will pass every test you write.

### Copying the book per read request

Correct and obviously too slow: a depth query would copy the whole book, and a metrics scrape
every 15 seconds would copy it again. The snapshot approach is the same idea with the copy moved
to the writer, done once per *batch* rather than once per *reader*, and bounded to the depth
anyone actually reads.

### Let readers queue a request onto the writer thread

The purest single-threaded design: readers submit a task, the writer answers it between batches.
Rejected because it puts reader work on the critical path — a slow or bursty reader now costs
matching latency directly — and it needs a queue, a future per request and a timeout policy, all
to answer a question the snapshot already answers for free.

## Consequences

**Good**

- Zero synchronisation in the matching path. The book is a plain data structure, which is also
  why JMH can benchmark it directly with no framework in the way.
- Readers cannot affect matching. A depth endpoint, a metrics scrape, or a badly behaved script
  is incapable of slowing the engine down or corrupting a book.
- The design scales horizontally without a code change: more partitions, more engine instances,
  more books in parallel.
- Price-time priority is guaranteed by construction, not by a lock discipline someone has to
  remember.

**Costs, accepted**

- **A single symbol cannot be scaled beyond one core.** If one instrument saturated a core, the
  answer would be to make the book faster, not to add threads to it. This is a real ceiling and
  the right one.
- **Depth data is up to one batch stale.** Intended. Nothing in the matching path reads it, and a
  client needing tick-by-tick depth subscribes to market data.
- **Books are in memory, so a restart or a partition reassignment loses them.** They are rebuilt
  by replaying the partition from the earliest offset (`auto-offset-reset=earliest`). This is
  only safe because trade ids are **deterministic** — `TradeIds.of(symbol, sequence)` — so a
  replay re-emits byte-identical trade ids and order-service's `(trade_id, order_id)` primary key
  deduplicates them. With random trade ids, a rebuild would double every position in the
  platform. The recovery story and the id-generation choice are one decision, not two.
- **Replay time grows with retention.** With 7-day retention on the accepted-orders topic, a
  cold start replays up to 7 days of orders for its partitions. The production answer is periodic
  book snapshots to a compacted topic, so a rebuild starts from the last snapshot and replays
  only the tail. Not built; recorded in docs/performance.md as the next piece of work.
- **The single-writer guarantee is only as strong as the keying.** A producer that published an
  order without the symbol as key would silently break it. That is why `partitionKey()` is a
  method on the event type itself rather than an argument at each producer call site (see
  `DomainEvent`), and why the accepted-orders and cancel-requests topics are consumed in **one**
  consumer group — two groups would let a cancel be handled by a thread that does not own the book.

## Notes for the C++ reader

The shape of this solution is one you would recognise immediately: shard books across threads by
symbol, give each thread its own queue, never share a book. What differs is who maintains it.
Kafka provides the sharding, the queues, the backpressure and the failover when an instance dies;
the price is that a partition reassignment can move a book to a different thread, which is why
deterministic trade ids and replay-based recovery exist.

Three Java-specific points worth carrying into an interview:

1. **Safe publication replaces reclamation.** The hard part of a C++ read-mostly structure is
   knowing when it is safe to free the old version — hazard pointers, epochs, RCU, or
   `shared_ptr` refcount traffic on the read path. Here the collector answers it, and the read
   path is a single volatile load. This is the one place the managed runtime is unambiguously
   ahead.
2. **`volatile` is not `std::atomic` with relaxed ordering.** Every Java volatile access is at
   least acquire/release and sequentially consistent with respect to other volatile accesses;
   there is no relaxed variant on a field. When you genuinely want the weaker orderings the tool
   is `VarHandle` (`getAcquire`, `setRelease`, `getOpaque`) — the right instrument, and
   unnecessary here because the publication happens once per batch rather than once per order.
3. **You cannot control layout, so you cannot hand-pack cache lines.** No `alignas(64)`, no
   control over field order, and padding fields can be reordered or elided. The only supported
   tool is `@jdk.internal.vm.annotation.Contended`, which needs `-XX:-RestrictContended` to work
   for application classes. This project does not use it, and the reasoning is in
   docs/concurrency.md: with one writer per book and no shared counters in the hot path, there is
   no line for two cores to fight over, so padding would be cargo cult. The place it *would*
   matter is the `volatile` snapshot field if several books' slots shared a line and several
   writers published frequently — measured, not assumed, before adding it.
