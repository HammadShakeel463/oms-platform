# Domain model

## 1. Ubiquitous language

| Term | Meaning here |
|---|---|
| **Instrument** | A tradeable equity, identified by `symbol`. Carries lot size, tick size, a reference price and a fat-finger band. |
| **Account** | The entity that owns orders and positions. A JWT maps to exactly one account (plus roles). |
| **Order** | An instruction to buy or sell a quantity of an instrument, at a limit price or at market. Has identity (`orderId`) and a lifecycle. |
| **clientOrderId** | The caller's own idempotency key. Unique per `(accountId, clientOrderId)`. |
| **Order book** | Per symbol: two sides, each a price-ordered set of price levels, each level a FIFO queue of resting orders. |
| **Aggressor / resting** | The incoming order that causes a match is the aggressor; the order already on the book is resting. Execution happens at the **resting** price. |
| **Fill / Trade** | A quantity matched between two orders at one price. `TradeExecutedEvent` carries both sides. |
| **Leaves quantity** | `quantity - filledQuantity`. The live residual. |
| **Position** | Net signed quantity per `(account, symbol)`, plus average cost. |
| **Realised P&L** | Locked in when a position is reduced. Average-cost method (ADR 0005, Phase 4). |
| **Unrealised P&L** | `(mark − avgCost) × signedQty`, marked to the latest tick mid. |

## 2. Order lifecycle

```
                 ┌──────────────────────────── REJECTED (terminal)
                 │                                  ▲
                 │                                  │ risk / reference data / engine reject
   ┌─────┐   ┌───┴──────┐   ┌────────┐   ┌──────────┴───────┐   ┌────────┐
   │ NEW │──▶│VALIDATED │──▶│ ROUTED │──▶│ PARTIALLY_FILLED │──▶│ FILLED │ (terminal)
   └──┬──┘   └───┬──────┘   └───┬────┘   └────────┬─────────┘   └────────┘
      │          │              │                 │      ▲
      │          │              │                 └──────┘  (further partial fills)
      └──────────┴──────────────┴─────────────────┴──────▶ CANCELLED (terminal)
```

The table is code, not prose: [`OrderStatus`](../oms-common/src/main/java/com/oms/common/domain/OrderStatus.java)
holds an `EnumMap<OrderStatus, Set<OrderStatus>>` and `canTransitionTo` is the only way a
transition is ever decided. Every attempted transition goes through it, and a rejected
transition throws `IllegalStateTransitionException` — it is a bug or a replay, never a
business outcome.

**Why the table lives in the shared module.** order-service owns the write side, but
order-service consumers (audit tooling, position-service reconciliation) must agree on what
a legal history looks like. Putting the table in `oms-common` makes "legal history" a single
definition rather than a convention.

### Audit trail

Every transition appends an immutable row to `order_audit`:

```
order_audit(id, order_id, seq, previous_status, new_status, reason,
            filled_qty, leaves_qty, avg_price_ticks, actor, occurred_at, trace_id)
```

Append-only, no `UPDATE`, no `DELETE`, enforced by a database rule in Phase 2. `seq` is a
per-order monotonic counter so the history is totally ordered even when two rows share a
millisecond. The current `orders` row is a *derived cache* of the last audit row — which
means the audit trail is the source of truth and can rebuild the order table, not the other
way round. That framing is what makes it an audit trail rather than a change log.

## 3. Value objects and why they are records

`OrderAcceptedEvent`, `TradeExecutedEvent`, `InstrumentView`, `QuoteSnapshot`,
`ApiError` — all `record`.

A `record` is a final class with final fields, a canonical constructor, and
compiler-generated `equals`/`hashCode`/`toString`. For a C++ reader: it is the aggregate
struct you would write with `= default`-ed comparison operators, except immutability is the
default rather than something you remember to add `const` for, and there is no copy
constructor to think about because there is no copying — references are shared and the
object cannot change underneath you.

Practical consequences worth knowing:

- **Validation goes in the compact canonical constructor.** See `OrderAcceptedEvent`. This
  is the record equivalent of validating in the constructor body: an invalid instance cannot
  exist, not even transiently.
- **Records are shallowly immutable.** A record holding a `List` still exposes the same list
  object. Where it matters, copy defensively (`List.copyOf`) in the canonical constructor.
- **Jackson deserialises records natively** (2.15+), using the canonical constructor. No
  setters, no no-arg constructor, no Lombok. Hence no Lombok anywhere in this project — a
  deliberate choice, since records plus a modern IDE cover the boilerplate that Lombok was
  invented for, and an interviewer can read the code without knowing an annotation processor.

## 4. Price and quantity representation

This is the single most consequential modelling decision in the project.

| Layer | Price type | Why |
|---|---|---|
| REST API, JSON | `BigDecimal` | Exact decimal. `"172.4500"` is unambiguous; `172.45` as a `double` is not. |
| PostgreSQL | `NUMERIC(18,4)` | Exact, indexable, what any auditor or reporting tool expects. |
| Matching engine hot path | `long` ticks at 4dp | No allocation, no `compareTo` dispatch, cache-friendly. |

`double` appears nowhere for money. In a C++ trading system this is reflexive; the Java
temptation is stronger because `BigDecimal` is verbose enough that people reach for `double`
to make the code read nicely.

[`Ticks`](../oms-common/src/main/java/com/oms/common/money/Ticks.java) is the only
conversion boundary — `fromDecimal` on the way in, `toDecimal` on the way out.

Two details that are worth defending:

- `fromDecimal` uses `RoundingMode.UNNECESSARY` and throws on excess precision. A client
  that sends `172.456789` gets a `400`, not a silently rounded order. Silent rounding of a
  client price is how you lose money invisibly.
- `notionalTicks` uses `Math.multiplyExact`. Java's `long` arithmetic wraps silently on
  overflow exactly like C++'s does for unsigned (and is UB for signed), so a fat-finger
  quantity could produce a *negative* notional that sails through a `notional < limit`
  risk check. `multiplyExact` turns that into an `ArithmeticException`. This is the
  `__builtin_mul_overflow` habit, spelled in Java.

**Quantity** is a plain `long` of shares, always positive; direction lives in `Side`.
`Side.sign()` (+1 / −1) keeps position arithmetic branch-free:
`signedQty += side.sign() * fillQty`.

## 5. Sealed interfaces for the event contract

```java
public sealed interface DomainEvent
        permits OrderAcceptedEvent, OrderCancelRequestedEvent, OrderCancelConfirmedEvent,
                OrderLifecycleEvent, TradeExecutedEvent, MarketTickEvent { ... }
```

A closed sum type. The direct C++ analogue is `std::variant<...>` over POD types: the set
of alternatives is fixed at compile time, and the compiler can verify you handled all of
them. Java 17 gives exhaustive `switch` over a sealed hierarchy with pattern matching:

```java
// no default branch needed — and adding a 7th event type makes this fail to compile
String describe(DomainEvent e) {
    return switch (e) {
        case OrderAcceptedEvent a        -> "new order " + a.orderId();
        case OrderCancelRequestedEvent c -> "cancel " + c.orderId();
        case OrderCancelConfirmedEvent c -> "cancelled " + c.orderId();
        case OrderLifecycleEvent l       -> l.previousStatus() + " -> " + l.newStatus();
        case TradeExecutedEvent t        -> "trade " + t.quantity() + " @ " + t.priceTicks();
        case MarketTickEvent m           -> "tick " + m.symbol();
    };
}
```

Compare this with the visitor pattern you would otherwise write, or with the
`instanceof`-chain plus `default -> throw new IllegalStateException()` that pre-17 Java
forces — the latter moves an error the compiler *could* have caught into production.

The sealing is also a governance statement: the set of published events is the platform
contract, and adding one is a deliberate, reviewable act rather than a class quietly
appearing in some service's package.

## 6. Error contract

One shape, every service, every failure:

```json
{
  "timestamp": "2026-09-28T09:15:00.123Z",
  "status": 422,
  "code": "RISK_LIMIT_BREACHED",
  "message": "Order notional 5,200,000.00 exceeds account limit 1,000,000.00",
  "path": "/api/v1/orders",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "fieldErrors": []
}
```

`code` is a stable enum name clients branch on; `message` is for humans; `traceId` turns a
support ticket into a trace lookup. The HTTP status is an attribute of
[`ErrorCode`](../oms-common/src/main/java/com/oms/common/error/ErrorCode.java) rather than a
decision made ad hoc at each throw site, which is what stops the same failure returning
`400` from one service and `422` from another.

Exceptions all extend `OmsException`, which carries the `ErrorCode`. They are unchecked —
see the class javadoc for why that is idiomatic rather than lazy.

## 7. Pre-trade risk checks (implemented in Phase 2)

| Check | Rule | Rejection |
|---|---|---|
| Max order value | `quantity × price ≤ account.maxOrderNotional` | `RISK_LIMIT_BREACHED` / `MAX_ORDER_VALUE` |
| Max position | `abs(currentSignedQty + orderSignedQty) ≤ account.maxPositionQty` | `RISK_LIMIT_BREACHED` / `MAX_POSITION` |
| Fat-finger band | `abs(price − referencePrice) / referencePrice ≤ instrument.priceBandPercent` | `RISK_LIMIT_BREACHED` / `PRICE_BAND` |
| Lot size | `quantity % instrument.lotSize == 0` | `VALIDATION_FAILED` |
| Tick size | `priceTicks % instrument.tickSizeTicks == 0` | `VALIDATION_FAILED` |
| Tradeable | `instrument.status == ACTIVE` | `MARKET_CLOSED` |

For a MARKET order, the price used for the notional and band checks is the current ask
(buy) or bid (sell) from the cached snapshot, widened by a configurable slippage factor.
A market order with no contra side quoted is rejected rather than routed blind.

Each check is a separate bean implementing one interface, composed in a list — so the risk
suite is ordered, individually testable, and each rejection is tagged in metrics by which
limit fired. Phase 2 shows the Spring idiom for injecting an ordered `List<RiskCheck>`.
