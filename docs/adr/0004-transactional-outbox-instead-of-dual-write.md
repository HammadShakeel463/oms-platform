# ADR 0004 — Transactional outbox instead of a dual write to Kafka

- **Status:** Accepted
- **Date:** 2026-09-28
- **Phase:** 2

## Context

Placing an order must do two things: write rows to PostgreSQL (the order, its audit row) and
publish `OrderAcceptedEvent` to Kafka so the matching engine works it. These are two
different systems with two different commit protocols, and there is no shared transaction
between them.

The obvious code is a dual write:

```java
@Transactional
public Order place(Command cmd) {
    Order order = repository.save(...);
    kafkaTemplate.send(ACCEPTED_TOPIC, event);   // <-- the bug
    return order;
}
```

Both failure directions are real, and both are unacceptable:

- **Send succeeds, transaction rolls back.** The engine works an order that does not exist in
  the database. It fills. Fills arrive for an unknown order. The account is now exposed to a
  position no record explains.
- **Transaction commits, send fails** (or the process dies in between). The order sits in the
  database in ROUTED for ever, visible to the client, never worked by anyone. It will not
  fill and it will not cancel, because the engine has never heard of it.

Moving the send after the commit does not fix it; it only changes which failure you get.
Nothing about ordering the two operations makes them atomic, because they are not.

## Decision

Write the event to an `outbox` table **in the same transaction as the business change**, and
let a separate poller publish it.

```java
@Transactional
public Order place(Command cmd) {
    Order order = repository.save(...);
    auditRepository.save(...);
    outboxWriter.enqueue(ACCEPTED_TOPIC, event, order.getId());  // just another INSERT
    return order;
}
```

The `outbox` table carries `event_id`, `topic`, `partition_key`, a `jsonb` payload, the trace
id, and `published_at`/`attempts`/`last_error`.

`OutboxPublisher` runs on a fixed **delay** (200ms in production config, 50ms in tests) and:

1. claims a batch with
   `SELECT ... WHERE published_at IS NULL ORDER BY created_at LIMIT n FOR UPDATE SKIP LOCKED`;
2. sends each row to Kafka with `event.partitionKey()` as the message key;
3. blocks on the broker acknowledgement;
4. stamps `published_at` in the same transaction that holds the row locks.

A second scheduled job purges rows published more than `outbox-retention` (3 days) ago.

Three details carry most of the weight:

- **`FOR UPDATE SKIP LOCKED`** is what makes this safe with several order-service replicas.
  A plain `SELECT` would have every replica publish every event; a plain `FOR UPDATE` would
  serialise the replicas behind each other. `SKIP LOCKED` gives each replica a disjoint batch
  and lets them run in parallel — PostgreSQL acting as a concurrent work queue, with the
  arbitration in the database rather than in a coordination service.
- **`fixedDelay`, not `fixedRate`.** Fixed rate schedules from the previous *start*, so a slow
  batch causes runs to overlap and pile up. Fixed delay measures from the previous
  *completion* and self-throttles under load.
- **The ack is awaited before stamping `published_at`.** Stamping first would lose any event
  the broker never actually accepted.

## Alternatives considered

**Dual write.** Rejected: see Context. It is not a small risk taken knowingly; it is a
correctness bug that appears exactly when the system is already unhealthy.

**Kafka transactions (`KafkaTemplate.executeInTransaction`) plus a JDBC transaction,
chained.** Spring can synchronise the two so the Kafka transaction commits just after the
JDBC one. This narrows the window but does not close it — a crash between the two commits
still leaves a committed row whose event was never published. Narrower is not atomic, and
"very unlikely" is a property that stops holding at volume.

**XA / two-phase commit across PostgreSQL and Kafka.** Genuinely atomic and genuinely not
worth it: a transaction manager to operate, in-doubt transactions to resolve by hand, a
latency cost on every order, and a coordinator whose failure blocks both systems. This is the
classic case where the coordination protocol is more dangerous than the problem.

**Debezium change-data-capture on the outbox table.** The same pattern with a lower-latency,
higher-machinery reader: Debezium tails the write-ahead log instead of polling. It is
strictly better at scale and it is what I would use for a high-volume production system.
Rejected here because it adds Kafka Connect plus a connector to operate and configure for
what is, at this volume, a few milliseconds of latency on a path that already costs more than
that. **The design is deliberately CDC-ready**: the outbox row already carries topic,
partition key and payload, so swapping the poller for Debezium changes no application code.

**Publish on `TransactionalEventListener(AFTER_COMMIT)`.** Tidier-looking, and it fixes the
"send succeeds, transaction rolls back" direction. It does nothing about the other direction:
the listener runs in the same process, after the commit, and a crash at that moment loses the
event with no record that it was ever owed.

## Consequences

**Good**

- "Order persisted" and "engine told" are now genuinely atomic — one local transaction.
- The outbox is a durable, queryable record of what should have been published. During an
  incident, `SELECT * FROM outbox WHERE published_at IS NULL` is the answer to "what is
  stuck", and the backlog is exported as a gauge so it can be alerted on.
- Kafka being down degrades rather than fails: orders are still accepted and persisted, the
  backlog grows, and it drains when the broker returns. Without the outbox, a broker outage
  is an order-entry outage.
- Retries are free and safe — the row is simply still unpublished.

**Costs, accepted**

- **Publication is at-least-once, never exactly-once.** The process can publish and die before
  stamping `published_at`, and the next poll republishes. This is why every consumer in the
  platform is idempotent (`docs/kafka-event-design.md` §4). The outbox does not remove the
  need for idempotent consumers — it makes it mandatory.
- Latency: up to one poll interval (200ms worst case) between commit and publish. Irrelevant
  next to the network round trip the client already pays, and tunable.
- One more table, one more scheduled job, one more thing to monitor.
- Ordering is per partition key and only as good as the claim order. Rows are claimed
  `ORDER BY created_at` and the producer is idempotent with in-flight pipelining, so
  per-key order is preserved — but two events written in one transaction are published in
  creation order, not simultaneously, and a consumer must not assume otherwise.

## Notes for the C++ reader

This is a write-ahead log applied to messaging, and it is the same reasoning as a journal in
a storage engine: you cannot make two independent systems commit atomically, so you make one
of them durable first and derive the other from it. The outbox row is the journal entry; the
Kafka publish is the checkpoint that replays it.

The instinct this fights is the one that reaches for a lock or a barrier to make two
operations appear simultaneous. Across process and network boundaries there is no such
primitive — the only tools are idempotency and replay. Most distributed-systems design is
about arranging to need nothing stronger.
