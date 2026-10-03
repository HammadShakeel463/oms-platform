# Interview talking points

The ten questions this project will actually attract, with answers I can defend — and, where it
applies, the specific place my C++ background changed the decision rather than just decorating it.

**How to use this document.** Each answer has a **30-second version** (what you say first) and a
**if they dig** section (where you go when they follow up). Interviewers interrupt; the short
answer has to stand alone. Every number here is traceable to
[performance.md](performance.md) or a test in the repository — do not round them up in the room.

**The one rule:** never claim something the repository does not do. Three things in this project
are unverified or refuted, and all three are *written down*
([deployment.md §7](deployment.md#7-what-is-not-yet-verified), [performance.md §5](performance.md#5-the-negative-result-node-pooling-did-not-do-what-i-expected)).
Volunteering them is a stronger signal than hoping nobody checks.

---

## 1. "Walk me through what happens when a client places an order."

**30 seconds.** `POST /api/v1/orders` hits the gateway, which validates an RS256 JWT and rate
limits per account. order-service validates the request, looks up the instrument (Redis-cached),
runs three pre-trade risk checks, writes the order at status `NEW`, an audit row, and an *outbox*
row — all in one PostgreSQL transaction — and returns `201`. A poller publishes the outbox row to
`orders.accepted.v1`, keyed by symbol. matching-engine consumes that partition, matches against an
in-memory price-time book, and emits `trades.executed.v1`. order-service consumes its own fills
back to move the order to `PARTIALLY_FILLED` or `FILLED`; position-service consumes the same topic
and updates net quantity, open cost and realised P&L.

**If they dig — the three things in that flow that are design decisions, not plumbing:**

- **The order is durable before the HTTP response returns, and the Kafka publish is not in the
  transaction.** That is the transactional outbox ([ADR 0004](adr/0004-transactional-outbox-instead-of-dual-write.md)).
- **Every topic on the order path is keyed by symbol**, which is what gives the engine a single
  writer per book (question 2) and also stops a cancel from overtaking the order it cancels.
- **A risk rejection is a `201`, not a `400`** — the order exists, with status `REJECTED` and an
  audit row saying which limit fired. A rejected order a client cannot see afterwards is a support
  ticket. That is why `OrderService` is annotated
  `@Transactional(noRollbackFor = RiskRejectedException.class)`: the exception unwinds the call
  stack but must not unwind the row that records it.

> **C++ contrast worth drawing:** that last point is where Java's exception/transaction
> interaction has no C++ analogue at all. In C++ an exception unwinding is purely a stack
> concern — destructors run, nothing external rolls back. In Spring, throwing past a
> `@Transactional` boundary marks the transaction rollback-only *as a side effect of the throw*.
> The control flow and the durability decision are coupled, and `noRollbackFor` is how you
> decouple them. This is the single most surprising thing I hit coming from C++.

---

## 2. "You say the matching engine takes no locks. How does that work?"

**30 seconds.** It is a partitioning result, not a locking trick. All orders and cancels for a
symbol carry the symbol as the Kafka key, so they land on one partition; a partition is consumed
by exactly one member of the consumer group, on one thread. So **one symbol has one writer, by
construction** — and a mutable `TreeMap` book with no synchronisation is correct, because nothing
else ever touches it. Readers (the depth REST endpoint, the Prometheus scrape) never read the
book: the writer publishes an immutable `BookSnapshot` through a `volatile` field after each
batch, and readers read that.

**If they dig — why `volatile` is sufficient and not merely "probably fine":**

The write to `volatile BookSnapshot snapshot` is a release; the read is an acquire. Everything the
writer did to build that snapshot *happens-before* the reader's observation of the reference. The
snapshot is deeply immutable (records over unmodifiable lists), so there is no later mutation to
be seen. This is the Java Memory Model's **safe publication** idiom, and it is exactly
`std::atomic<T*>::store(p, std::memory_order_release)` paired with
`load(std::memory_order_acquire)`.

Three sharpening points that signal you know the model rather than the keyword:

- **`volatile` is not C++ `volatile`.** C++ `volatile` says nothing about inter-thread ordering —
  it is for memory-mapped I/O, and using it for threading is a known bug. Java `volatile` is a
  full ordering primitive, closest to `std::atomic` with `seq_cst` on the field itself.
- **Java has no `relaxed`.** The field access *is* the fence. Where C++ lets me choose
  `memory_order_release` precisely, Java gives me `volatile` or `VarHandle` — and I used
  `volatile` because the publication is once per batch, not per operation, so the barrier cost is
  amortised to nothing.
- **A passing test does not prove the absence of a race.** `SnapshotPublicationConcurrencyTest`
  runs one writer and four readers for 1.5 s asserting self-consistency on every read, and that is
  empirical companionship, not proof — a broken publication passes on x86, where stores are
  already ordered, and fails on ARM. The proof is the JMM argument above. **The tool I have not
  used and should is jcstress**, which is Java's equivalent of reasoning about
  `memory_order_acquire` and then running ThreadSanitizer. Saying that unprompted is worth more
  than the test.

---

## 3. "You have lock-free experience in C++. Why isn't the order book lock-free?"

This is the question the project is built to attract, and **the answer is that lock-free would be
wrong, not that it would be hard.**

**30 seconds.** A limit order book exists to establish one total order: price, then arrival time.
Concurrent mutation of a single book destroys the thing the book is for. Two threads matching
against the same price level cannot agree on which resting order was first without serialising on
that level — and once you have serialised on the level, you have a lock, you have just spelled it
`CAS` and added an ABA problem. So the concurrency win has to come from **partitioning across
symbols**, which is embarrassingly parallel and needs no synchronisation at all. HBL and ENGRO
never interact. That is where the parallelism is, and it is free.

**If they dig — the things I would say next, in order:**

- **A lock-free book also makes the audit trail unimplementable.** Every real venue has to answer
  "why did this order fill before that one"; a non-deterministic interleaving means the answer is
  "it depended on timing", which is not an answer a regulator accepts.
- **What I did instead is the industry answer**: single-threaded matching per book, with the
  throughput coming from running many books. LMAX reached six million orders a second on *one*
  thread for exactly this reason.
- **Where lock-free would belong** is the handoff *into* the engine — a bounded ring buffer between
  the Kafka consumer and the matching thread, which is a classic SPSC queue. That boundary is not
  the bottleneck here (p99 is 500 ns; the network is four orders of magnitude slower), so building
  it would be optimising the wrong thing.

> **The C++ answer that is actually the same answer.** The last four lock-free structures I wrote
> in C++ were all *handoffs* — SPSC and MPSC queues between pipeline stages — not shared mutable
> aggregates. That is the pattern: lock-free is for moving data between owners, not for sharing
> ownership. This project applies the same rule and lands on single-writer. Being able to say
> "I didn't build the lock-free thing *and here is the discipline that told me not to*" is the
> strongest version of this answer. ([ADR 0005](adr/0005-single-writer-per-book-not-a-lock-free-order-book.md))

---

## 4. "What happens to an order if Kafka is down when the request arrives?"

**30 seconds.** Nothing is lost and the client still gets a `201`. The order row, the audit row and
the outbox row are written in one local transaction; publishing happens afterwards from the outbox.
If Kafka is unreachable, rows accumulate unpublished and the poller retries. The alternative —
save to the database, then call `KafkaTemplate.send()` — is a **dual write**: two systems, no
shared transaction, and a crash between them either loses an accepted order or publishes an order
that does not exist. Both are real-money bugs.

**If they dig:**

- **The poller is `SELECT ... FOR UPDATE SKIP LOCKED`**, which is what makes it safe to run on
  every replica at once: each instance takes a disjoint batch and nobody blocks. Without
  `SKIP LOCKED` the replicas serialise behind one row lock, and the outbox becomes the bottleneck
  it was supposed to remove.
- **The order path is at-least-once by design** (question 5). Publish-then-mark-published means a
  crash between those two steps re-publishes — which is correct, because the consumers are
  idempotent.
- **The failure this creates, and the alarm I wrote for it.** Kafka being down does not make order
  entry fail, so orders keep returning `201` and *nothing works them*. The first symptom would
  otherwise be a customer asking why their order never filled. `OutboxBacklogGrowing` is the most
  valuable rule in `ops/observability/rules.yml` for exactly that reason, and
  `oms_outbox_backlog` is the gauge behind it.

> **C++ contrast:** this is the class of problem I had no equivalent for. Durability in the
> trading systems I worked on was a sequenced multicast feed plus a journal — the ordering
> guarantee came from the wire protocol. Here the guarantee has to be manufactured out of a
> database transaction and a retry loop, and the interesting work is in the *boundary between*
> two systems that cannot share a transaction, not inside either one.

---

## 5. "You're at-least-once. How do you avoid double-counting a fill?"

**30 seconds.** Idempotency on natural keys, not on a bookkeeping table. `order_fill` has primary
key `(trade_id, order_id)`, so a replayed trade is a duplicate-key violation that is caught and
counted on `oms_fills_duplicate_total` rather than applied twice. position-service applies fills
through the same trade identity. **The whole scheme depends on trade ids being deterministic**, so
they are:

```java
UUID.nameUUIDFromBytes((symbol + '\u0000' + sequence).getBytes(UTF_8))
```

**If they dig — this is the strongest 90 seconds in the project, so take it:**

The matching engine holds books in memory. Recovery is therefore a partition replay from Kafka.
Replay re-emits every trade. With `UUID.randomUUID()`, every re-emitted trade looks new, every
consumer applies it again, and **every position in the platform doubles** — while the engine
itself looks perfectly correct. The money is wrong and the logs are clean. That is the worst
failure mode a system can have.

Which means **recovery design and id generation are one decision, not two** — and that is the
sentence to land. A random id is a perfectly reasonable local choice that silently makes the global
recovery story unsound.

Two more details that show it is thought through rather than lucky:
- the separator is `'\u0000'`, so `("AB", 1)` and `("A", "B1")` cannot collide;
- the sequence is a deterministic function of the input stream, so a replay is byte-identical, and
  `TradeIdsTest` asserts exactly that.

**Why not Kafka exactly-once semantics?** Because EOS covers the Kafka read–process–write cycle
and stops at the edge of PostgreSQL — which is where the money lands. It would buy a transactional
guarantee on the part of the path I am *least* worried about, cost throughput, and still leave me
needing the idempotent write. Idempotent consumers subsume what EOS would have given me.

---

## 6. "Why `long` ticks instead of `BigDecimal`? Why not `double`?"

**30 seconds.** `double` is disqualified outright — binary floating point cannot represent 0.01,
and money that does not add up is not a performance question. `BigDecimal` is correct but is a
*heap object per arithmetic operation*: in a matching loop that is sustained allocation pressure,
and allocation pressure in Java is a GC pause you did not schedule, landing in the p99 of something
unrelated. So inside the engine a price is a `long` of 1/10,000ths — `Ticks.SCALE = 4` — and
`BigDecimal` appears only at the API and database boundary, where one object per request is
irrelevant and exactness in JSON and `NUMERIC` is what matters.

**If they dig:**

- **Overflow is checked, not hoped for.** `Ticks.notionalTicks` uses `Math.multiplyExact`, which
  throws on overflow — the direct equivalent of `__builtin_mul_overflow`. A silently wrapped
  notional is a risk check that passes when it should not fire.
- **`Ticks.fromDecimal` uses `RoundingMode.UNNECESSARY`**, so a price with more precision than the
  tick scale throws instead of being quietly rounded. Silent rounding at the boundary is how a
  reconciliation break gets created.
- **`NO_PRICE = Long.MIN_VALUE`** is a sentinel for "market order, no limit", rather than a
  `Long` that can be null — because a nullable price in an inner loop is a null check per
  comparison and a boxing allocation per value.
- **The measured consequence**, from [performance.md §6](performance.md#6-allocation-by-variant):
  the tuned book allocates 47.3 B/op against the baseline's 163.9 B/op, and — the better
  property — **the tuned book's allocation is depth-independent** (47.3 → 48.1 across a tenfold
  depth change) while the baseline's *grows* (163.9 → 200.3).

> **C++ contrast, stated as a limitation rather than a brag:** fixed-point `int64` money is simply
> the default in the systems I come from, so this was not a discovery. The genuinely new reasoning
> was *where to stop*. In C++ I would have pushed the representation all the way to the edge,
> because there is no cost to it. In Java the boundary is where `BigDecimal`'s exactness in JSON
> and JDBC is worth more than the object it costs, and `Ticks` is deliberately a leaf utility with
> no dependencies so both sides can use it. ([ADR 0002](adr/0002-fixed-point-money-long-ticks-in-the-engine-bigdecimal-at-the-boundary.md))

---

## 7. "You benchmarked it. What did you measure, and did anything surprise you?"

**30 seconds.** JMH, three variants of the same contract — all passing the same 27 contract
tests — across two workloads and two book depths. **The headline is a slope, not a ratio:** when
depth goes up tenfold, the tuned book loses 4% of its throughput and the baseline loses 78%. That
is O(1) against O(n) on the cancel path, and it is the fix that mattered. A single-point speedup
can be argued about; a difference in *scaling* cannot.

**And yes — one hypothesis was refuted by its own benchmark.** I expected pooling `OrderNode`
objects to improve throughput and tail latency. It cut allocation 30% (47.3 vs 67.2 B/op) and did
**nothing measurable** to either: 9.29M vs 9.40M ops/s, with the unpooled variant nominally
*higher* and both well inside the error bars.

**If they dig — why it was wrong, which is the actually interesting part:**

The generational hypothesis holds almost perfectly here. A young-generation copying collector only
does work proportional to *survivors*; dead objects cost nothing to reclaim, because the space is
recovered by moving a pointer. A pooled node's entire purpose is to avoid allocating an object that
would die within microseconds — so removing that allocation removes almost no work. **This is the
precise inversion of the C++ intuition**, where allocation cost is paid at `malloc` and again at
`free`, and pooling a short-lived object is close to a guaranteed win. In Java, dying young *is*
the optimisation.

What I did about it: kept the mechanism (configurable, and `steadyStateReusesNodes` proves 500
quote/pull cycles create exactly one node), and wrote in
[performance.md §5](performance.md#5-the-negative-result-node-pooling-did-not-do-what-i-expected)
that **the benchmark does not justify it**, with the three conditions under which it would — a
shared production heap, a concurrent collector, a larger live set — named as the follow-up
measurement. If a run under G1 with the full service shows nothing either, the pool should be
deleted.

**The second thing worth volunteering: the baseline's mean latency is 11.1 µs and its median is
200 ns — a factor of 55.** 75% of operations in that mix are cheap and the 25% that are cancels
cost tens of microseconds, so the average describes neither population; no individual operation
ever experiences it. That is why `oms_engine_match` publishes a real percentile histogram and why
the alert threshold is on p99.

**And what I did not measure**, said before they ask: NAIVE changes four things at once, so I
cannot separate the `BigDecimal` cost from the `ArrayList` cost — the fourth variant that would
isolate it is one class and one `@Param`. The host is a Windows workstation whose
`System.nanoTime()` granularity is ~100 ns, which is why the tuned percentiles land on suspiciously
round figures. The collector is SerialGC, deliberately, and is not production.

---

## 8. "This is your first Java project. Where did you have to stop writing C++?"

Answer this one *concretely*. A vague "Java is more about frameworks" answer wastes the best
opportunity in the interview; four specific things you got wrong first and then fixed is a
credibility multiplier.

**1. I reached for manual composition and had to learn what the container buys.** My instinct was
to construct the object graph myself in `main`, which is what I would do in C++. What constructor
injection actually buys is not the wiring — it is that `@Transactional`, `@Cacheable` and
`@Observed` are *proxies*, and a bean has to be obtained from the container for them to exist at
all. **The trap I hit:** a `@Transactional` method called from another method of the same class
does not start a transaction, because the call does not go through the proxy. That has no C++
equivalent; the nearest thing is a vtable you only get if someone else constructed you.

**2. There is no RAII, so there is no transaction scope guard.** In C++ lifetime *is* the
mechanism: a destructor commits or rolls back and the compiler guarantees it runs. Java's
equivalent is declarative and proxy-based, which means the boundary is a *configuration* decision
instead of a lexical one — and that is why `Propagation.MANDATORY` on the internals of
`OrderService` is load-bearing. It makes "this must run inside a caller's transaction" a
constraint the framework enforces at runtime, recovering a fraction of what the type system gave
me for free in C++.

**3. I under-estimated identity.** `equals`/`hashCode` on JPA entities, with a generated id that
is null before the flush, is a genuine trap — an entity put in a `HashSet` before persist becomes
unfindable after it. In C++ I control when the object becomes canonical; in JPA the persistence
context does. Composite keys and `instanceof` pattern matching for equality are where that shows
up in this codebase.

**4. I wrote a `List<Fill>` return type first, then deleted it.** The engine now pushes primitives
to a listener, because the C++ habit of "return a vector, the caller moves it" has no move
semantics behind it in Java — it is an allocation the caller then walks. Same intent, different
cost model.

**The honest framing:** the systems thinking transferred almost completely — memory model,
false sharing, percentiles over averages, measure-don't-guess. What did not transfer was the
*ecosystem*: auto-configuration, proxying, the annotation-driven boundaries. That is the gap I
closed building this, and it is the gap worth being open about, because it is the one they are
actually probing.

---

## 9. "Why five services? Couldn't this be a monolith?"

**30 seconds.** For this load, yes, and I would say so. The boundaries are drawn where they are
because of **different runtime shapes**, not to be fashionable: the matching engine is stateful,
in-memory and scales by Kafka partition; market-data is a fan-out with a streaming problem;
position-service is a pure event-sourced consumer with no public writes. Those three want
different deployment, different memory profiles and different scaling triggers — matching-engine's
HPA is capped at 12 because that is the partition count, and a 13th pod would sit idle, which is
not a sentence that makes sense for a monolith.

**If they dig — the two things that make this a real boundary rather than a package rename:**

- **One PostgreSQL role per service, with no cross-schema grants.** order-service *physically
  cannot* read `oms_position`. The convention "schema per service, no shared tables" is enforced
  by the absence of a `GRANT`, which means the well-meaning cross-service join fails at the
  database instead of becoming load-bearing. **The absence of the grant is the mechanism.**
- **`oms-common` holds contracts only** — enums, `Ticks`, the sealed `DomainEvent` hierarchy,
  topic names — and has no JPA and no Spring Boot dependency. ArchUnit tests enforce that. A
  shared module that gains an `@Entity` is how five services quietly become one distributed
  monolith with five deployment pipelines.

**And the cost, stated plainly:** an end-to-end order is now eventually consistent, a developer
needs the whole compose stack to run a flow, and the failure modes are distributed-systems failure
modes — which is exactly why the outbox, the idempotency and the tracing-across-Kafka work had to
be done rather than assumed. ([ADR 0001](adr/0001-microservice-boundaries-and-a-shared-contract-module.md))

---

## 10. "What's wrong with it? What would you do differently with a real deadline?"

Have this answer ready and lead with the most serious item. A candidate who cannot criticise their
own project reads as someone who has not operated one.

**The three I volunteer:**

1. **The container, cluster and CI paths have never been executed.** There is no Docker daemon on
   the machine this was authored on, so the Dockerfile, compose stack, Kubernetes manifests, GitHub
   Actions workflow and five Testcontainers integration tests are written and statically checked
   and *not run*. [deployment.md §7](deployment.md#7-what-is-not-yet-verified) lists them in
   priority order. The Java build is green — 330 unit tests — and I am precise about which of those
   two statements I am making.
2. **Cold-start recovery is the weakest number in the system.** Books are in memory, so recovery
   replays the partition from Kafka — up to seven days of retention. The fix is periodic book
   snapshots to a compacted topic so a rebuild replays only the tail. This is a worse problem than
   anything on the performance page, and it is the next thing I would build.
3. **The node pool is in the codebase and its benchmark does not justify it** (question 7). I kept
   it because it is cheap and the measurement that would settle it is specific and named; a reviewer
   is entitled to tell me to delete it.

**And what is deliberately absent, because scope is a decision too:** no FIX protocol (the brief is
REST/Kafka, and a FIX engine is a project in itself), no multi-leg or stop/iceberg order types, no
settlement or clearing, no corporate actions, no real market data vendor, and the gateway signs
with an **ephemeral** RSA key generated at startup — tokens do not survive a restart, which is
correct for a demo and wrong for production, where this would be a KMS or an external identity
provider ([ADR 0007](adr/0007-asymmetric-jwt-verified-at-every-service.md)).

**If asked for one thing at real scale:** I would not start with the matching engine. At 13
operations per microsecond in steady state it is four orders of magnitude faster than the network
in front of it. I would start with the order *path* — the outbox poller's latency, the JSON
serialisation, and the Postgres write amplification from three rows per order — because that is
where the end-to-end number actually lives, and the engine is already not the bottleneck at any
volume this platform will see.

---

## The three sentences, if you only get three

1. **All orders for a symbol share a Kafka partition, so the matching engine has one writer per
   book by construction and takes no lock at all** — readers get an immutable snapshot published
   through a `volatile`, which is the JMM's safe publication and the same reasoning as
   `release`/`acquire` in C++.
2. **Trade ids are derived from `(symbol, sequence)` rather than random, because the books are in
   memory and recovery is a replay** — with random ids a rebuild would double every position in
   the platform while the engine still looked correct.
3. **I measured the engine, and one of my own hypotheses was refuted** — pooling cut allocation
   30% and did nothing to throughput, because a generational collector reclaims dead objects for
   free, which is the inverse of the C++ intuition. I kept the mechanism and wrote down that the
   data does not support it.

---

## Traps — where a weak answer loses the room

| They ask | The answer that loses | The answer that wins |
|---|---|---|
| "How fast is it?" | "100× faster than the baseline." | "Two numbers, and I will tell you which one I defend: 5.9× on the mean in steady state, ~109× on an accumulating book — and the one that matters is that the tuned book loses 4% across a 10× depth change where the baseline loses 78%." |
| "What's your average latency?" | Quoting a mean. | "I would not specify it that way. In my own benchmark the baseline's mean was 55× its median. I publish a histogram and alert on p99." |
| "Is it lock-free?" | "I could make it lock-free." | "It should not be. Concurrent mutation of one book destroys the price-time ordering the book exists to establish, and makes the audit trail unimplementable. The parallelism is across symbols." |
| "Did you use exactly-once?" | "Yes, Kafka EOS." | "No — EOS stops at the edge of PostgreSQL, which is where the money lands. At-least-once plus idempotent writes on natural keys." |
| "Why not use `double` for price?" | "Precision issues." | "Binary floating point cannot represent 0.01. It is a correctness disqualification, not a precision trade-off." |
| "Coverage?" | "Over 70%." | "The gate is 70% and CI enforces it — and coverage is a floor, not evidence. The tests I would point at are the 20,000-operation book invariant test and the publication concurrency test." |
| "Have you run it?" | Implying yes. | "The Java build, yes — 330 tests. The container and cluster paths, no, and `docs/deployment.md §7` says so in priority order." |

---

## Questions to ask them

Signals domain seriousness, and all four are things this project made me want to know:

1. Where does the ordering guarantee live in your system — the wire protocol, the broker, or the
   database? (It is the question that determines everything else.)
2. How do you reconcile positions, and what is your break process when the trading system and the
   back office disagree?
3. What is your p99 target, and is it specified on the venue round trip or on your own hop?
4. Are you on FIX, and is the engine something you own or something you integrate with?

---

## Related reading

| Question | Document |
|---|---|
| 1 | [architecture.md](architecture.md), [order-service.md](order-service.md) |
| 2, 3 | [concurrency.md](concurrency.md), [ADR 0005](adr/0005-single-writer-per-book-not-a-lock-free-order-book.md) |
| 4, 5 | [kafka-event-design.md](kafka-event-design.md), [ADR 0004](adr/0004-transactional-outbox-instead-of-dual-write.md) |
| 6 | [ADR 0002](adr/0002-fixed-point-money-long-ticks-in-the-engine-bigdecimal-at-the-boundary.md) |
| 7 | [performance.md](performance.md), [matching-engine.md](matching-engine.md) |
| 9 | [ADR 0001](adr/0001-microservice-boundaries-and-a-shared-contract-module.md), [domain-model.md](domain-model.md) |
| 10 | [deployment.md §7](deployment.md#7-what-is-not-yet-verified), [security.md](security.md) |
