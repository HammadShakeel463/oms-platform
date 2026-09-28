# Architecture

## 1. The shape of the system

```
                              ┌──────────────────────────────┐
   REST (JWT) ───────────────▶│         api-gateway          │  :8080
                              │  Spring Cloud Gateway        │
                              │  routing · JWT · rate limit  │
                              └───────┬──────────┬───────────┘
                                      │          │
                    ┌─────────────────┘          └──────────────┬─────────────────┐
                    ▼                                           ▼                 ▼
          ┌───────────────────┐                     ┌────────────────────┐  ┌──────────────┐
          │   order-service   │  :8081              │ market-data-service│  │position-svc  │
          │  intake·validate  │                     │  tick simulator    │  │ positions    │
          │  risk·state m/c   │                     │  snapshot+stream   │  │ P&L          │
          │  audit trail      │                     │                    │  │              │
          └───┬───────────┬───┘                     └────┬─────────┬─────┘  └──────┬───────┘
              │           │ REST (reference data)        │         │               │
              │           └──────────────────────────────┘         │               │
              │ Kafka                                              │ Kafka         │ Kafka
              │ orders.accepted                                    │ ticks         │ trades
              │ orders.cancel-requests                             ▼               │
              ▼                                              ┌──────────┐          │
     ┌────────────────────┐   trades.executed                 │  Redis   │          │
     │  matching-engine   │───────────────────────────────────┴──────────┘◀─────────┘
     │  :8082             │   execution-reports
     │  in-memory book    │──────────────────────────▶ order-service
     │  price-time        │
     └────────────────────┘

   PostgreSQL: schema per service (oms_order, oms_marketdata, oms_position). No shared tables.
```

A rendered Mermaid version lives in [diagrams/architecture.mmd](diagrams/architecture.mmd).

## 2. Service responsibilities and ownership

| Service | Port | Owns (state) | Synchronous in | Consumes (Kafka) | Produces (Kafka) |
|---|---|---|---|---|---|
| api-gateway | 8080 | nothing | all client traffic | – | – |
| order-service | 8081 | `oms_order` schema: orders, order_audit, outbox | `POST/GET /api/v1/orders` | `execution-reports`, `trades.executed` | `orders.accepted`, `orders.cancel-requests`, `orders.lifecycle` |
| matching-engine | 8082 | in-memory order books only | read-only book depth endpoint | `orders.accepted`, `orders.cancel-requests` | `trades.executed`, `execution-reports` |
| market-data-service | 8083 | `oms_marketdata` schema: instruments; Redis snapshots | `GET /api/v1/instruments`, `/quotes` | `trades.executed` (to print last trade) | `marketdata.ticks` |
| position-service | 8084 | `oms_position` schema: positions, trade_ledger | `GET /api/v1/positions` | `trades.executed`, `marketdata.ticks` | – |

Two rules make this a microservice architecture rather than a distributed monolith:

1. **One writer per piece of state.** `oms_order.orders` is written by order-service and by
   nothing else, ever. Other services learn about orders from the lifecycle topic.
2. **Services share contracts, never persistence models.** `oms-common` contains records,
   enums and the error contract. It has no `spring-boot-starter-data-jpa` dependency, so it
   is structurally incapable of holding an entity. This is enforced by an ArchUnit test in
   Phase 2 rather than left to code review.

## 3. Why the boundaries fall where they do

The split is not one-service-per-noun. It follows three different axes:

**Consistency boundary.** The order lifecycle needs a transaction: persisting a state
change and recording the audit row must be atomic, or the audit trail becomes fiction.
That is one service with one database.

**Performance boundary.** The matching engine needs microsecond-scale, single-threaded-
per-book execution with no I/O in the critical section. Putting it behind the same
process as JPA and HTTP thread pools would mean its tail latency is hostage to somebody
else's GC pause and connection pool. So it is its own process, its state is in memory,
and it never touches a database on the hot path. This is the same reasoning that puts a
matching core on a pinned thread in a C++ system; here the isolation is a process
boundary instead of a core affinity mask.

**Fan-out boundary.** Market data is high-volume, conflatable and read by everything.
Positions are low-volume, strictly ordered and read by few. Different scaling profiles,
different services.

## 4. Synchronous vs asynchronous — the rule applied

REST is used only where the caller genuinely cannot proceed without an answer:

- client → gateway → any service (the client is waiting)
- order-service → market-data-service for instrument reference data during validation
  (and even that is Redis-cached, with a stale-tolerant fallback)

Kafka is used for everything else, because every other interaction is a fact being
announced rather than a question being asked. `TradeExecutedEvent` is not a request to
position-service; it is a statement that a trade happened. If position-service is down,
the trade still happened, and the event waits in the log. That is the property a REST call
cannot give you and it is why an order path that spans four services still has exactly one
synchronous hop.

## 5. The order flow, end to end

```
1. POST /api/v1/orders          gateway validates JWT, extracts accountId + roles, routes
2. order-service                bean validation → instrument lookup (Redis/REST)
                                → pre-trade risk (notional, position, price band)
                                → INSERT order (NEW), INSERT audit, INSERT outbox row
                                   ── all in one transaction ──
                                → transitions to VALIDATED, then ROUTED
3. outbox publisher             publishes OrderAcceptedEvent to orders.accepted (key=symbol)
4. matching-engine              consumes, matches against the book for that symbol
                                → emits TradeExecutedEvent per fill (key=symbol)
                                → emits OrderCancelConfirmedEvent for IOC/FOK residual
5. order-service                consumes trades → PARTIALLY_FILLED / FILLED + audit rows
                                → emits OrderLifecycleEvent
6. position-service             consumes trades → updates position, realised P&L
                                consumes ticks  → updates unrealised P&L
7. market-data-service          consumes trades → prints last trade into the tick stream
```

Step 2–3 is the transactional outbox. The alternative — write the row, then publish to
Kafka — has no atomicity: a crash between the two leaves an order that exists in the
database but was never worked, or a fill for an order nobody has a record of. The outbox
turns "database write + event publish" into a single local transaction plus a retryable
read, which is the only honest way to do it without distributed transactions. ADR 0004
records this.

## 6. What is deliberately *not* here

Worth being able to say out loud in an interview, because the absence is a decision:

- **No service discovery / Eureka.** Kubernetes Services already give stable DNS names.
  Adding Eureka would be a second, redundant discovery mechanism.
- **No Spring Cloud Config server.** ConfigMaps and Secrets do the job; a config server is
  one more thing to keep highly available.
- **No Avro / Schema Registry.** See ADR 0003 — the tradeoff is explicit, not accidental.
- **No Saga orchestration framework.** The one multi-service invariant (an order and its
  fills) is handled by the outbox plus idempotent consumers. A saga engine would be
  ceremony around a two-step flow.
- **No real exchange connectivity (FIX).** The matching engine *is* the venue here, which
  is what makes the concurrency work demonstrable.

## 7. Cross-cutting concerns

| Concern | Mechanism | Phase |
|---|---|---|
| Authentication | JWT validated at the gateway, propagated as a signed header + re-validated per service | 5 |
| Authorisation | `@PreAuthorize` role checks; `ROLE_TRADER`, `ROLE_RISK`, `ROLE_ADMIN` | 5 |
| Validation | Jakarta Bean Validation on request records, `@Valid` at the controller | 2 |
| Error contract | `ApiError` + `@RestControllerAdvice` per service | 2 |
| Schema migration | Flyway, one migration path per schema | 2 |
| Caching | Redis: instrument reference data (long TTL), quote snapshots (short TTL) | 4 |
| Idempotency | `eventId` dedupe table per consumer + natural keys | 2–4 |
| Metrics | Micrometer → Prometheus; custom timers on the matching hot path | 3, 6 |
| Tracing | Micrometer Tracing → OTLP; trace id propagated over Kafka headers | 6 |
| Logging | Structured JSON (logstash encoder), trace id and orderId in MDC | 6 |
