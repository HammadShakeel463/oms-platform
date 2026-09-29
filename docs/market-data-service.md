# market-data-service

Instrument reference data, a simulated tick feed, Redis-cached quote snapshots, and a streaming
quote endpoint whose backpressure design is the interesting part.

## Package layout

```
com.oms.marketdata
├── domain/        InstrumentEntity — the only @Entity, maps to the shared InstrumentView at the boundary
├── repository/    Spring Data
├── reference/     InstrumentService — Redis-cached reference lookups
├── simulator/     SymbolState (single-writer random walk), TickGenerator (the scheduler)
├── quote/         QuoteCache — write-through quote snapshots in Redis
├── stream/        ConflatingMailbox, Subscription, TickBroadcaster — transport-agnostic backpressure
├── api/           instrument, snapshot and SSE endpoints
├── messaging/     TickPublisher (out), TradePrintListener (in)
└── config/        typed properties, cache, Kafka
```

`stream` depends on neither Spring MVC nor the servlet API, and `MarketDataArchitectureTest`
enforces it. The mailbox is a backpressure mechanism, not an SSE implementation — which is what
makes it unit-testable with no container and reusable if the transport ever changes.

## API

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/v1/instruments` | full universe, Redis-cached 1 h |
| `GET` | `/api/v1/instruments/{symbol}` | **on the order path** — order-service calls this during validation |
| `GET` | `/api/v1/quotes/{symbol}` | point-in-time snapshot: live state first, Redis second |
| `GET` | `/api/v1/quotes/stream?symbols=HBL,OGDC` | SSE stream, conflating |

```bash
curl -N 'http://localhost:8083/api/v1/quotes/stream?symbols=HBL'
```

```
event:quote
id:1841
data:{"symbol":"HBL","bid":172.4400,"bidSize":500,"ask":172.4600,"askSize":300,
      "last":172.4500,"lastSize":100,"mid":172.4500,"spread":0.0200,
      "sequence":1841,"asOf":"2026-09-29T09:15:00.123Z"}

:heartbeat
```

`sequence` is on the wire deliberately — see [§Backpressure](#backpressure-the-part-worth-reading).

## Backpressure: the part worth reading

A feed produces ticks faster than a slow subscriber consumes them. There are exactly four
options, and three are wrong:

| Strategy | Outcome |
|---|---|
| **Unbounded queue** | Memory grows until the process dies. The subscriber at fault is fine; everyone else is killed by the OOM. This is what a naive implementation does by default, and it is the worst option because it fails the *service* rather than the *client*. |
| **Block the producer** | One slow subscriber stalls tick generation for everybody. Textbook backpressure applied to the wrong kind of stream — right for a work queue, catastrophic for a broadcast. |
| **Bounded queue, drop newest** | Bounded, but the subscriber is left holding *stale* quotes while fresh ones are thrown away. Worse than useless for pricing: a quote known to be old is more dangerous than no quote. |
| **Conflate — drop oldest, per symbol** ✅ | Memory bounded by the **symbol universe**, not the tick rate, and a subscriber that falls behind receives the freshest price for every symbol it missed. |

The fourth is correct here because of a property of this specific data: **a tick supersedes its
predecessor.** Nobody pricing an order cares what the bid was 40 ms ago. Tick streams are
conflatable in a way trade streams emphatically are not — which is exactly why
`oms.trades.executed.v1` is consumed at-least-once with full ordering and this stream is not.

### How `ConflatingMailbox` works

```
latest : ConcurrentHashMap<symbol, tick>   last value per symbol, overwritten in place
dirty  : LinkedBlockingQueue<symbol>       which symbols have something unread
queued : Set<symbol>                       guard so a symbol is enqueued at most once
```

The `queued` set is what actually bounds it. Without it, a symbol ticking 1,000 times while a
subscriber stalls would put 1,000 entries in `dirty` — bounded memory for the *ticks*, unbounded
for the *notifications*, which is the same bug wearing a hat.
`ConflatingMailboxTest.memoryIsBoundedBySymbolCount` offers 100,000 ticks to a subscriber that
never reads and asserts exactly 5 pending entries.

**The one subtle ordering.** `take()` clears the dirty flag *before* reading `latest`. The other
order loses updates: a tick arriving in between would set `latest`, find the symbol still flagged,
decline to enqueue, and never be delivered. Clearing first means the worst case is one redundant
delivery of a still-current value. **Lost updates are unacceptable; a duplicate is harmless.**
`noLostUpdatesUnderContention` hammers that path 20 times over 5,000 ticks.

### Conflation is published, not hidden

Every event carries `sequence`. A jump from 41 to 58 tells the client 16 updates were superseded.
**Silent conflation would be a lie; conflation with a sequence number is a documented delivery
model.** `oms.marketdata.conflated.total` exports it as a metric, so "clients cannot keep up" is a
number on a dashboard rather than a silence.

## Virtual threads, and what they do *not* fix

Each subscription needs a thread blocked on its mailbox. On platform threads that caps concurrent
subscribers at the pool size and costs ~1 MB of stack each; a virtual thread parked on a blocking
read costs a few hundred bytes of heap.

`QuoteStreamController` injects a Spring `AsyncTaskExecutor` rather than calling
`Executors.newVirtualThreadPerTaskExecutor()`. With `spring.threads.virtual.enabled=true` Boot
backs it with virtual threads; with it off, a platform pool. Same bytecode. That buys two things:
tests run the delivery loop on platform threads for deterministic stack traces, and an operator can
switch the feature off in production without a rebuild.

**Virtual threads raise the ceiling on how many subscribers can be parked. They do nothing about a
subscriber that reads slowly** — that is the mailbox's job. Either mechanism alone is insufficient:
virtual threads with an unbounded queue still exhausts memory, and a conflating queue on a
200-thread pool still caps subscribers at 200.

## The simulator

`SymbolState` is a mean-reverting random walk, **one writer** (the scheduler thread), with an
immutable `QuoteSnapshot` published through a `volatile` field — the same pattern as the matching
engine ([ADR 0005](adr/0005-single-writer-per-book-not-a-lock-free-order-book.md)).

Three decisions worth defending:

- **Mean reversion, not a pure random walk.** An unbounded walk eventually drifts outside the
  fat-finger band and then *every order in that symbol is rejected* — the demo dies after twenty
  minutes. `SymbolStateTest.meanReversionKeepsPricesPlausible` runs 20,000 ticks and asserts the
  mid stays inside the 10% band.
- **Seeded from the symbol**, so a restart replays the same path. An integration test can assert on
  the feed and a demo looks the same twice.
- **The trade-print handover is a single-slot atomic reference, not a lock.** The Kafka listener that
  consumes executed trades runs on a different thread. Instead of synchronising the walk state, it
  drops the print into `AtomicReference` and the generator applies it on its next pass. That is
  *correct*, not just convenient: "last traded price" is last-value-wins by definition, so a print
  superseded before the next tick is genuinely uninteresting. A `synchronized` block would have
  given a Kafka consumer thread the ability to block tick generation.

`fixedDelay`, not `fixedRate` — same reasoning as the outbox publisher: fixed rate schedules from
the previous *start*, so an overrun causes runs to pile up. The cost is cadence drift under load,
which for a simulator is the right trade.

## Quote caching

Redis, written through on every tick, **not** `@Cacheable`. The annotation models read-through
caching; this is a publication, and a lazily-populated cache would only ever hold symbols somebody
had already asked for.

Three reasons the cache exists at all, given the state is already in memory: any instance can
answer (the gateway does not know which instance generates which symbol), a restart is not a
blackout (order-service prices market orders off this endpoint), and it is the shared read model for
anything that does not want to subscribe.

**The TTL is a safety mechanism, not a memory one.** If the generator stops — crash, halt, deploy —
an entry with no TTL would sit in Redis and be served as current for ever. The TTL converts "the
feed died" into "no quote available", which is an answer a caller can act on. A stale price served
confidently is the failure mode that costs money.

For the same reason `QuoteController` returns **404 rather than a fabricated price** when neither
live state nor cache has the symbol.

## Kafka

| Direction | Topic | Notes |
|---|---|---|
| out | `oms.marketdata.ticks.v1` | `acks=1`, fire-and-forget, `linger.ms=5` |
| in | `oms.trades.executed.v1` | last-trade print; `auto-offset-reset=latest` |

**`acks=1` is the only place in the platform where durability is deliberately traded for
throughput**, and it is worth being able to justify: a tick is superseded in 100 ms, the topic has
one-hour retention, and nothing reconstructs state from it. Paying replication latency on the
highest-volume topic to protect data obsolete before it could be replayed is the wrong trade.
Failures are counted (`oms.marketdata.ticks.failed`), so "the feed is lossy" stays visible.

`auto-offset-reset=latest` on the trade consumer, because the last-trade field is a live value, not
a ledger — replaying a session of prints to arrive at the current one would be waste.

The trade-print consumer is the one consumer in the platform that is **naturally idempotent**: its
effect is an assignment. No `processed_event` table, no natural-key constraint — both would be
ceremony around last-value-wins.

## Configuration

| Property | Default | Notes |
|---|---|---|
| `oms.marketdata.simulator-enabled` | `true` | off for a test that publishes its own ticks |
| `oms.marketdata.tick-interval` | 100 ms | fixed **delay** |
| `oms.marketdata.spread-ticks` | 2 | quoted spread, in instrument ticks |
| `oms.marketdata.max-step-ticks` | 3 | largest single mid move |
| `oms.marketdata.quote-cache-ttl` | 30 s | after this, "no quote" rather than a stale price |
| `oms.marketdata.stream-timeout` | 30 m | server closes the stream; clients reconnect |
| `oms.marketdata.stream-heartbeat` | 15 s | stops intermediate proxies timing out an idle stream |
| `spring.threads.virtual.enabled` | `true` | **on** here, off in order-service |

The stream timeout is bounded on purpose: an SSE connection never closed leaks a subscription and a
thread when a client vanishes without a FIN, which is the normal outcome of a laptop lid closing.
Clients reconnect; servers do not get to leak. All three emitter callbacks (`onCompletion`,
`onTimeout`, `onError`) unregister — handling only the first is the standard way to leak an SSE
subscription.

## Tests

```bash
./mvnw -pl market-data-service -am test      # 34 tests, no Docker
./mvnw -pl market-data-service -am verify    # adds MarketDataFlowIT — needs Docker
```

| Test | What it protects |
|---|---|
| `ConflatingMailboxTest` | Memory bounded by symbols, freshest-wins, sequence gaps observable, no lost update under contention |
| `TickBroadcasterTest` | Filtering, fan-out, lazy reaping of dead subscriptions, producer never blocked while subscribers churn |
| `SymbolStateTest` | Mean reversion inside the risk band, tick-grid alignment, determinism, print conflation |
| `MarketDataArchitectureTest` | `stream` is transport-agnostic, simulator does no I/O, no floating-point money |
| `MarketDataFlowIT` | Flyway seeding, tick-to-Kafka, Redis write-through, and the **SSE stream end to end** |

The SSE test is the one that cannot be faked by a unit test: async dispatch, the task executor and
the emitter lifecycle are all container behaviour.
