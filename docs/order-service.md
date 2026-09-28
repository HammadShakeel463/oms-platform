# order-service

The write side of the order lifecycle. Every order is born here, validated here,
risk-checked here, and every state change is journalled here before anyone else hears
about it.

## Package layout

```
com.oms.order
├── api/              controllers, DTOs, the global exception handler, the trace-id filter
├── config/           typed properties, HTTP client, cache, Kafka, scheduling
├── domain/           JPA entities — the only package with @Entity in it
├── messaging/        outbox writer, outbox publisher, Kafka listeners
├── reference/        instrument lookup (remote + cached, or a static catalogue)
├── repository/       Spring Data interfaces
├── risk/             the pre-trade risk engine
│   └── checks/       one class per rule
└── service/          transaction boundaries and orchestration
```

Dependencies point inwards. `domain` knows nothing about `api`; `api` never touches
`repository`. Both rules are enforced by `ArchitectureTest`, not by convention.

## API

Base path `/api/v1/orders`. Until Phase 5, the caller's account arrives as the
`X-Account-Id` header — gateway-injected in the finished system, and read in exactly one
place so the swap to a JWT claim is a one-line change.

| Method | Path | Status | Notes |
|---|---|---|---|
| `POST` | `/api/v1/orders` | 201 + `Location` | Places an order |
| `GET` | `/api/v1/orders/{orderId}` | 200 / 404 | Scoped to the calling account |
| `GET` | `/api/v1/orders` | 200 | `?symbol=&status=&page=&size=` |
| `DELETE` | `/api/v1/orders/{orderId}` | **202** | Requests a cancel — see below |
| `GET` | `/api/v1/orders/{orderId}/audit` | 200 | Full immutable history |

### Place an order

```bash
curl -i -X POST http://localhost:8081/api/v1/orders \
  -H 'Content-Type: application/json' \
  -H 'X-Account-Id: ACC-TRADER-1' \
  -H 'X-User-Id: hammad' \
  -d '{
        "clientOrderId": "cl-0001",
        "symbol": "HBL",
        "side": "BUY",
        "orderType": "LIMIT",
        "timeInForce": "DAY",
        "limitPrice": "172.4500",
        "quantity": 1000
      }'
```

```json
HTTP/1.1 201 Created
Location: http://localhost:8081/api/v1/orders/1f7c...
{
  "orderId": "1f7c...", "clientOrderId": "cl-0001", "accountId": "ACC-TRADER-1",
  "symbol": "HBL", "side": "BUY", "orderType": "LIMIT", "timeInForce": "DAY",
  "limitPrice": 172.4500, "quantity": 1000, "filledQuantity": 0,
  "leavesQuantity": 1000, "avgPrice": null, "status": "ROUTED",
  "createdAt": "2026-09-28T09:15:00.123Z"
}
```

### Why cancel returns 202, not 200

The matching engine owns the book, so only the engine knows whether a cancel actually
landed — the order may be filling at the instant the request arrives. `DELETE` publishes
`OrderCancelRequestedEvent` and returns the order **still ROUTED**. It becomes `CANCELLED`
only when `OrderCancelConfirmedEvent` comes back. Returning 200 here would mean telling a
client their order is dead while their money moves.

### Error contract

Every failure, at every status, comes back as the same body:

```json
{
  "timestamp": "2026-09-28T09:15:00.123Z",
  "status": 422,
  "code": "RISK_LIMIT_BREACHED",
  "message": "Order notional 6898000.0000 exceeds the account limit 5000000.0000 for ACC-TRADER-1",
  "path": "/api/v1/orders",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

| Status | `code` | Cause |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Bean Validation; `fieldErrors[]` names each field |
| 400 | `MALFORMED_REQUEST` | Unparseable JSON, unknown enum value, bad UUID |
| 404 | `ORDER_NOT_FOUND` | Unknown order — *or another account's order* |
| 404 | `INSTRUMENT_NOT_FOUND` | Unknown symbol |
| 409 | `DUPLICATE_CLIENT_ORDER_ID` | `clientOrderId` reused for this account |
| 409 | `ILLEGAL_STATE_TRANSITION` | e.g. cancelling a FILLED order |
| 409 | `CONCURRENT_MODIFICATION` | Optimistic lock lost; reload and retry |
| 422 | `RISK_LIMIT_BREACHED` | A pre-trade check refused it |
| 503 | `UPSTREAM_UNAVAILABLE` | Reference data unreachable and not cached |

A 500 carries **only** the trace id — no class names, no SQL, no stack. The detail is in
the log line with the same id.

## Schema `oms_order`

| Table | Purpose |
|---|---|
| `account` | Risk limits. Read on every order, written rarely. |
| `orders` | Current state. A **derived cache** of the last `order_audit` row. |
| `order_audit` | Append-only history. UPDATE/DELETE rejected by a trigger. |
| `order_fill` | One row per execution. PK `(trade_id, order_id)` = the idempotency key. |
| `position_exposure` | This service's own exposure read model — net + working quantity. |
| `outbox` | Transactional outbox (ADR 0004). |
| `processed_event` | Consumer dedupe where no natural key exists. |

Details that are load-bearing rather than decorative:

- **`ix_orders_live`** is a partial index over `status IN ('ROUTED','PARTIALLY_FILLED')`.
  "Show me working orders" is the hot query and live orders are a small fraction of the
  table after a few trading days.
- **`ck_orders_limit_price`** refuses a LIMIT order with no price and a MARKET order with
  one. The application checks it too; an invariant that lives only in application code is
  one a future migration script will violate.
- **`trg_order_audit_immutable`** raises on UPDATE or DELETE. `OrderPersistenceIT` proves
  it by issuing native SQL that bypasses JPA entirely — which is exactly what an ad-hoc
  psql session would do.
- **`ix_outbox_unpublished`** is partial on `published_at IS NULL`, so the poller's query
  stays proportional to the backlog rather than to the table.

## Order flow

```
POST /api/v1/orders
  │
  ├─ Bean Validation ......................... 400 on failure
  ├─ account lookup .......................... 404 / 403
  ├─ duplicate clientOrderId check ........... 409
  ├─ instrument lookup (Redis → REST) ........ 404 / 503
  │
  ├─ INSERT orders (NEW)          ┐
  ├─ INSERT order_audit (seq 1)   │
  ├─ pre-trade risk               │   one transaction
  │    └─ rejected → REJECTED + audit, COMMIT, throw 422
  ├─ transition VALIDATED + audit │
  ├─ INSERT outbox (accepted)     │
  ├─ transition ROUTED + audit    │
  └─ UPDATE position_exposure     ┘
         │
         └─ OutboxPublisher (every 200ms) → oms.orders.accepted.v1  [key: symbol]

matching engine → oms.trades.executed.v1
  └─ EngineEventListener → TradeApplicationService (one transaction)
       ├─ INSERT order_fill (PK (trade_id, order_id) — replay-safe)
       ├─ roll the volume-weighted average price
       ├─ transition PARTIALLY_FILLED / FILLED + audit + lifecycle outbox
       └─ UPDATE position_exposure (working → net)
```

## Pre-trade risk

Six checks, each a Spring bean implementing `RiskCheck`, injected as an ordered
`List<RiskCheck>` and run fail-fast.

| Order | Check | Rule |
|---|---|---|
| 10 | `INSTRUMENT_TRADEABLE` | status must be ACTIVE |
| 20 | `LOT_SIZE` | `quantity % lotSize == 0` |
| 30 | `TICK_SIZE` | `limitPriceTicks % tickSizeTicks == 0` (LIMIT only) |
| 40 | `PRICE_BAND` | `abs(price − reference) ≤ reference × band%` |
| 50 | `MAX_ORDER_VALUE` | `quantity × price ≤ account.maxOrderNotional` |
| 60 | `MAX_POSITION` | `abs(worstCaseAfter(side, qty)) ≤ account.maxPositionQty` |

Two points worth raising unprompted in an interview:

**`MAX_POSITION` uses worst-case exposure, not current position.** The question is not
"where is this account now" but "where could this order put it": net position, plus every
working order on the same side, plus this one. Checking only filled quantity lets an
account place ten orders that individually pass and collectively breach the limit ten
times over. That is the single most common way a naive risk check is wrong, and
`RiskChecksTest.workingQuantityCounts` is the test that pins it.

**`MAX_ORDER_VALUE` treats overflow as a rejection.** `Ticks.notionalTicks` uses
`Math.multiplyExact`, so a fat-finger quantity throws instead of wrapping to a *negative*
notional — which would otherwise pass every `notional < limit` comparison ever written.

Exposure comes from `position_exposure`, this service's own projection built from its own
fills. It is deliberately **not** a call to position-service: a risk check on the order
path must not have a synchronous dependency on another service's uptime.

## Configuration

| Property | Default | Notes |
|---|---|---|
| `oms.reference.source` | `static` | `static` = built-in PSX catalogue; `remote` = market-data-service |
| `oms.reference.market-data-url` | `http://localhost:8083` | |
| `oms.order.market-order-slippage` | `0.05` | Risk prices a MARKET order 5% through the reference |
| `oms.order.outbox-batch-size` | `200` | Rows claimed per poll |
| `oms.order.outbox-poll-interval` | `200` (ms) | Fixed **delay** |
| `oms.order.outbox-retention` | `3d` | Published rows kept this long |
| `spring.jpa.open-in-view` | `false` | Deliberately off |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Flyway owns the schema |

Profiles: `vthreads` enables virtual threads (requires a Java 21+ runtime),
`integration-test` is used by the Testcontainers suite.

## Tests

```bash
./mvnw -pl order-service -am test      # 49 unit tests, no Docker needed
./mvnw -pl order-service -am verify    # adds the *IT suite — needs Docker
```

| Test | Kind | What it actually protects |
|---|---|---|
| `RiskChecksTest` | pure | Every risk rule, including fat-finger and notional overflow |
| `RiskEngineTest` | pure | Ordering, fail-fast, metric tagging, defensive copy |
| `OrderEntityTest` | pure | VWAP maths, overfill refusal, illegal transitions |
| `OrderServiceTest` | Mockito | Orchestration; that a rejection is recorded *and* rethrown |
| `TradeApplicationServiceTest` | Mockito | Replay safety, maker/taker, partial→filled |
| `OrderControllerTest` | `@WebMvcTest` | Status codes, `Location`, and one error contract |
| `ArchitectureTest` | ArchUnit | ADR 0001 and 0002 enforced by the build |
| `OrderPersistenceIT` | Testcontainers | Flyway, constraints, the append-only trigger, `SKIP LOCKED` |
| `OrderFlowIT` | Testcontainers | HTTP → outbox → Kafka → fill → FILLED, with the audit trail |
