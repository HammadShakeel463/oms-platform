# Kafka topic and event design

## 1. Naming convention

```
oms.<aggregate>.<event>.v<major>
```

| Topic | Producer | Consumers | Key | Partitions | Retention | Cleanup |
|---|---|---|---|---|---|---|
| `oms.orders.accepted.v1` | order-service | matching-engine | `symbol` | 12 | 7 d | delete |
| `oms.orders.cancel-requests.v1` | order-service | matching-engine | `symbol` | 12 | 7 d | delete |
| `oms.orders.execution-reports.v1` | matching-engine | order-service | `symbol` | 12 | 7 d | delete |
| `oms.trades.executed.v1` | matching-engine | order-service, position-service, market-data-service | `symbol` | 12 | 30 d | delete |
| `oms.orders.lifecycle.v1` | order-service | audit/analytics | `orderId` | 12 | 30 d | delete |
| `oms.marketdata.ticks.v1` | market-data-service | position-service, subscribers | `symbol` | 12 | 1 h | delete |
| `*.dlt` | Spring Kafka error handler | operators | inherited | 3 | 30 d | delete |

Declared in [`Topics`](../oms-common/src/main/java/com/oms/common/event/Topics.java) and created by the
bootstrap job in `docker-compose.yml`; the same settings are in [ops/kafka-topics.md](../ops/kafka-topics.md).

**The major version is in the topic name, not only in the payload.** A breaking schema
change becomes a *new topic*, so during a rollout the producer can dual-write `v1` and `v2`
and consumers migrate independently. Versioning only inside the payload forces every
consumer to be upgraded before the producer can change — a lock-step deploy, which is the
thing microservices are supposed to avoid.

## 2. Partition count and key choice

Partition count is the unit of consumer parallelism: a partition is consumed by at most one
consumer in a group, so a 12-partition topic scales a matching-engine group to 12 instances
and no further. 12 is chosen because it is divisible by 1, 2, 3, 4, 6 and 12, so a consumer
group can be resized without leaving instances idle.

**The key is the ordering guarantee.** Kafka orders messages within a partition, not within
a topic, so "which key" *is* "what is ordered". The choices here:

### `symbol` for everything on the order path

All orders and cancels for one symbol land in one partition, therefore reach one consumer
thread, therefore are worked in a single total order. That gives the matching engine a
crucial property: **one book, one writer.** The engine does not need a lock around a book
because Kafka has already serialised access to it. The parallelism is across symbols, not
within one.

This is the same design as a C++ system that shards books across threads by symbol hash and
gives each thread an SPSC queue — except the sharding, the queue and the rebalancing on
instance failure are Kafka's job rather than yours. Worth stating plainly in an interview:
*the concurrency win here is a partitioning decision, not a clever lock.*

It also fixes an ordering bug that a naive design walks straight into. If cancels were keyed
by `orderId` and new orders by `symbol`, a cancel could be delivered on a different
partition and be processed *before* the order it cancels. Same key, same partition, no race.

### `symbol` for `trades.executed`, even though position-service aggregates per account

The obvious objection: positions are per `(account, symbol)`, so should trades not be keyed
by account?

No — and the reasoning is the interesting part. What actually has to be ordered is the
stream per `(account, symbol)` pair, because realised P&L under average cost is
order-dependent: buy 100@10 then buy 100@12 then sell 100@11 gives a different realised
figure than the same trades in another order. Position *quantity* is commutative; realised
P&L is not.

Every trade for a given `(account, symbol)` pair necessarily carries that symbol, so it
lands in that symbol's partition. Ordering per `(account, symbol)` is therefore already
guaranteed by symbol keying — for free, with no repartition hop and no second topic. The
only constraint it adds is that a partition must be processed by one thread at a time,
which is the Kafka consumer default anyway.

Keying by account would have been *worse*: hot accounts create partition skew, and a market
maker account could pin one partition at 100% while eleven sit idle. Symbol keying spreads
load along the axis that is naturally uniform.

### `orderId` for `orders.lifecycle`

The only ordering that matters for an audit stream is per-order. Keying by `orderId` also
leaves the door open to switching this topic to `cleanup.policy=compact` if we ever want
"latest state per order" as a queryable table (a KTable), since compaction keeps the last
value per key.

## 3. Event catalogue

All six are `record`s implementing the sealed `DomainEvent`. Every event carries
`eventId`, `occurredAt`, `schemaVersion` and declares its own `partitionKey()` — the key is
a property of the event type, not a decision made at each producer call site.

### `OrderAcceptedEvent` → `oms.orders.accepted.v1`
Order passed validation and risk; the engine is cleared to work it. Carries the full order
(side, type, TIF, limit price in ticks, quantity) so the engine needs **no** database and
**no** REST call to act — a self-contained command. Fat events are the right call on the hot
path; an event that forces a lookup has just reintroduced the synchronous coupling the topic
was meant to remove.

### `OrderCancelRequestedEvent` → `oms.orders.cancel-requests.v1`
A cancel request, keyed by symbol so it serialises against the new-order stream.

### `OrderCancelConfirmedEvent` → `oms.orders.execution-reports.v1`
The engine confirms residual quantity left the book, with a `CancelReason`
(`USER_REQUEST`, `IOC_RESIDUAL`, `FOK_UNFILLABLE`, `UNKNOWN_ORDER`, `SESSION_END`).

order-service does **not** move an order to `CANCELLED` on the client's request alone — it
waits for this confirmation. Otherwise an order can be marked cancelled in the database
while the engine fills it a microsecond later, and you have told a client their order is
dead while their money moved. The engine owns the book, so the engine owns the cancel
outcome.

### `TradeExecutedEvent` → `oms.trades.executed.v1`
One event carries **both sides** of the trade (`buyOrderId`/`buyAccountId`,
`sellOrderId`/`sellAccountId`). This is an atomicity decision, not a convenience: as two
messages, a consumer could observe a half-applied trade, and a risk report taken at that
instant would not balance. One message, one transaction, always balanced.

`sequence` is the engine's strictly increasing per-symbol counter — used by consumers to
reject replays and detect gaps. `priceTicks` is always the **resting** order's price, so
price improvement accrues to the aggressor (standard continuous-book behaviour).

### `OrderLifecycleEvent` → `oms.orders.lifecycle.v1`
Every state transition, with `previousStatus`, `newStatus`, `reason` and a per-order
monotonic `version`. Emitted from the same transaction that writes the audit row (outbox),
so the database history and the topic cannot disagree.

### `MarketTickEvent` → `oms.marketdata.ticks.v1`
Top of book plus last trade, with a per-symbol `sequence`. The highest-volume topic and the
only one where **conflation is correct**: a subscriber that falls behind wants the freshest
quote, not a faithful replay of stale ones. Retention is 1 hour because nobody replays
yesterday's ticks from this topic — that is what a tick store is for.

## 4. Delivery semantics

**At-least-once, with idempotent consumers.** Not exactly-once.

Producers are configured `acks=all`, `enable.idempotence=true`,
`max.in.flight.requests.per.connection=5`, `retries=Integer.MAX_VALUE`. That gives
idempotent *production*: no duplicates from producer retries, and ordering preserved per
partition despite pipelining.

Consumers commit offsets **after** the handler succeeds (`ack-mode: RECORD` or
`MANUAL_IMMEDIATE`), never before. So a crash mid-handler replays the record. Every consumer
is therefore written to tolerate replay:

| Consumer | Idempotency mechanism |
|---|---|
| matching-engine ← accepted | `orderId` already known to the book → ignore (in-memory set of live + recently-done ids) |
| order-service ← trades | `UNIQUE(order_id, trade_id)` on the fill table; conflict → no-op |
| position-service ← trades | `processed_event(event_id)` table written in the same transaction as the position update |
| market-data ← trades | last-trade print is last-value-wins; a replay is harmless by construction |

**Why not exactly-once?** Kafka's EOS (transactional producer + `read_committed`) works, and
it is the right answer for a pure Kafka-to-Kafka topology. It does not extend to a
PostgreSQL write, which is where the money actually lands — a Kafka transaction cannot
commit a JDBC transaction atomically. So the honest options are a distributed transaction
(slow, operationally painful) or at-least-once plus idempotent writes. The second is what
real payment and trading systems run, because *idempotency is a property you want anyway*:
it also makes operator replays, DLT reprocessing and backfills safe. Exactly-once semantics
would be a weaker guarantee bought at a higher price.

This is worth contrasting with the C++ instinct. In a ZeroMQ pipeline you reach for sequence
numbers and a gap-detect/request-resend protocol because the transport gives you nothing.
Kafka gives durable ordered replayable partitions, so the engineering moves from *detecting
loss* to *tolerating repetition*.

## 5. Error handling, retries and the DLT

Spring Kafka's `DefaultErrorHandler` with a `DeadLetterPublishingRecoverer`:

```
attempt → fail → backoff (exponential, 100ms → 10s, 5 attempts) → still failing → <topic>.dlt
```

The retry classification matters more than the retry count:

- **Retryable** (transient): `OptimisticLockingFailureException`,
  `DataAccessResourceFailureException`, Redis timeouts, `UpstreamUnavailableException`.
  Back off and try again.
- **Not retryable** (poison): `JsonProcessingException`, `IllegalArgumentException`,
  `IllegalStateTransitionException`. Retrying a malformed message 5 times just delays the
  inevitable and blocks the partition while it does — these go straight to the DLT.

Blocking retries are used deliberately rather than Spring's non-blocking
`@RetryableTopic`. Non-blocking retry re-publishes to a retry topic, which **breaks
per-partition ordering** — a retried order could be worked after an order that arrived later.
On the order path that is unacceptable. Head-of-line blocking is the lesser evil, and it is
bounded by the backoff budget.

The DLT is not a graveyard: a DLT record retains its original headers
(`kafka_dlt-original-topic`, `-partition`, `-offset`, `-exception-message`), so a fixed bug
is followed by a replay from the DLT back to the source topic. Alerting is a Prometheus rule
on `spring_kafka_dlt_total > 0` — a message in a DLT is a page, not a metric nobody reads.

## 6. Consumer group layout

| Group | Topic(s) | Instances | Notes |
|---|---|---|---|
| `matching-engine` | accepted, cancel-requests | 1–12 | Both topics in one group so a symbol's orders and cancels land on the same instance |
| `order-service-fills` | trades.executed, execution-reports | 1–12 | Writes fills and drives the state machine |
| `position-service-trades` | trades.executed | 1–12 | Separate group → independent offsets from order-service |
| `position-service-marks` | marketdata.ticks | 1–12 | Separate group *and* separate consumer: ticks must never delay trade processing |
| `marketdata-prints` | trades.executed | 1–3 | Last-trade print |

Three separate groups read `trades.executed`. That is the whole point of a log over a queue:
each group has its own offsets, consumes at its own pace, and can be replayed from the
beginning independently. With a traditional queue this would be three queues and a fan-out
exchange to keep in sync.

`position-service` deliberately uses **two** consumers in two groups. Ticks arrive at
thousands per second and trades at tens; sharing a listener container would let a tick
backlog delay a trade, which delays a position update, which delays a risk decision.
Separate containers, separate thread pools, and the tick consumer is the one allowed to fall
behind.

## 7. Schema evolution rules

1. Additive, optional fields only within a major version. New consumers read old events;
   old consumers ignore the new field (`FAIL_ON_UNKNOWN_PROPERTIES=false`).
2. Never rename, never retype, never repurpose a field. That is a new `vN+1` topic.
3. Never remove a field within a major version.
4. `schemaVersion` is bumped only for breaking changes — which, by rule 2, means a new topic
   anyway. It exists so a consumer reading a DLT or an archive can tell what it is looking at.
5. Enums are the sharp edge: a producer sending a new `CancelReason` breaks an old consumer
   that deserialises strictly. Consumers therefore handle unknown enum values by mapping to a
   documented fallback (`READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE`) rather than throwing.

The contract is guarded by a test in `oms-common`
([`EventContractTest`](../oms-common/src/test/java/com/oms/common/event/EventContractTest.java)):
if Jackson cannot round-trip a record, the build fails in the module that owns the contract —
not at runtime in five services.

## 8. Topic design rationale, condensed

For the interview: five decisions, each with the alternative rejected.

| Decision | Alternative | Why this one |
|---|---|---|
| Key by `symbol` on the order path | Key by `orderId` | Gives one-writer-per-book for free; stops cancels overtaking orders |
| Both trade sides in one event | One event per side | Atomicity — no consumer can see half a trade |
| At-least-once + idempotent consumers | Kafka exactly-once | EOS does not extend to the PostgreSQL write; idempotency is needed anyway for replays |
| Blocking retry, then DLT | `@RetryableTopic` non-blocking | Non-blocking retry breaks per-partition ordering, which the order path depends on |
| Major version in the topic name | Version only in the payload | Lets producer and consumers migrate independently instead of in lock-step |
