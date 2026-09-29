# position-service

Consumes fills, derives positions, computes realised P&L on an average-cost basis and unrealised
P&L marked to the latest tick.

Every row this service owns is **derived** from `oms.trades.executed.v1`. The topic is the source of
truth; the tables are a materialised read model that can be rebuilt by replaying it from the
beginning — which is why trades are retained for 30 days and this consumer starts at `earliest`.

## Package layout

```
com.oms.position
├── domain/        PositionEntity (the arithmetic), TradeLedgerEntry
├── repository/    Spring Data
├── service/       PositionService (transaction boundary), MarkPriceCache (in-memory marks)
├── messaging/     PositionEventListener — the exhaustive switch over sealed DomainEvent
├── api/           positions and P&L endpoints
└── config/        two Kafka consumers with deliberately different policies
```

`domain` depends on neither Spring nor the repositories, enforced by `PositionArchitectureTest`.
The average-cost arithmetic has to be testable as plain arithmetic with no container.

## The arithmetic

### Why the state is `(netQuantity, openCost)`, not `(netQuantity, avgCost)`

The textbook representation stores an average cost. It is wrong in a way that only shows up after a
few thousand fills: every increase recomputes the average from the previously **rounded** average,
so error compounds. Storing the exact total cost of the open quantity makes the average a derived,
display-only value:

- increasing is `openCost += price × quantity` — exact, **no division**;
- reducing needs exactly one division, and only one;
- a **full close removes exactly the cost that went in**, so a flattened position has
  `openCost = 0` to the last paisa.

That last property is enforced by the schema, not by hope:

```sql
CONSTRAINT ck_position_flat_has_no_cost CHECK (net_quantity <> 0 OR open_cost = 0)
```

A rounding bug therefore **fails the write** rather than leaving a fraction of a rupee of phantom
cost for somebody to find in a reconciliation six months later.
`PositionEntityTest.noRoundingDrift` runs 1,000 fills at `33.3333` and asserts the average is still
exactly `33.3333`; `fullCloseBalancesExactly` closes a position built from two prices that do not
divide evenly and asserts zero.

Average-cost accounting inherently needs a division per reduction, so a rounding policy is
unavoidable: HALF_UP at 8 dp, residual absorbed into realised P&L. FIFO or specific-lot would avoid
the division by tracking individual lots — materially more state, and a *different accounting
method* rather than a more accurate version of this one.

### The case implementations get wrong: crossing through zero

Selling 150 against a long of 100 is **two things at once**: a close of 100 that realises P&L, and
the opening of a new short of 50 whose cost basis is *this fill's price*, not the old average.

```
long 100 @ 100.00, then SELL 150 @ 110.00

realised  = (110 − 100) × 100 = 1,000     ← on the 100 actually held
net       = −50
avgCost   = 110.00                        ← the NEW short, at this fill's price
```

Implementations that treat a fill as merely "same side or opposite side" go wrong one of two ways:
realise P&L on all 150 (inventing profit on quantity never owned), or carry the long's average onto
the new short (mispricing it for ever). `PositionEntityTest.CrossingZero` pins both, and
`flipAndBackReconciles` does the end-to-end check a trader would: after returning to flat, realised
P&L must equal **total proceeds minus total cost**, with no reference to averages at all.

### Unrealised P&L

```java
(mark − avgCost) × netQuantity
```

The signed quantity does the work for both directions — a long with the mark above cost is
positive × positive; a short with the mark below cost is negative × negative. **No branch on side**,
which is one fewer place to get a sign wrong.

`unrealisedPnl(null)` returns **null, not zero**, for an unmarked open position. "Unknown" and
"nothing" are different statements, and a risk screen that renders an unmarked position as flat is
exactly how somebody concludes they have no exposure. The same reasoning drives
`AccountPnlResponse.complete` and `unmarkedSymbols`: a total that silently omits a position is worse
than no total.

## Ordering, and why symbol keying is sufficient

Average-cost P&L is **order dependent**: buy 100@10, buy 100@12, sell 100@11 realises a different
figure from the same three fills in another order. Position *quantity* is commutative; realised P&L
is not.

So what must be ordered is the stream per `(account, symbol)` — and since every trade for such a
pair necessarily carries that symbol, keying the topic by **symbol** already guarantees it. No
repartition hop, no second topic. Account keying would have been *worse*: a market-maker account
would pin one partition while the others idled. Full argument in
[kafka-event-design.md §2](kafka-event-design.md).

## Schema `oms_position`

| Table | Purpose |
|---|---|
| `position` | Current holding. `(net_quantity, open_cost, realised_pnl)` + counters. |
| `trade_ledger` | One immutable row per `(trade, account)`. **Append-only, trigger-enforced.** |

`trade_ledger` does two jobs at once:

1. **Idempotency.** The primary key `(trade_id, account_id)` is what makes at-least-once delivery
   safe. A replayed event conflicts and the handler treats the conflict as "already applied". A
   natural key beats a separate bookkeeping table, because the row that records the effect *is* the
   record that it happened — the two cannot drift.
2. **Audit.** `position` is a running total; this is the workings. Storing `net_after` and
   `avg_cost_after` per fill means a disputed P&L figure is traced fill by fill rather than
   recomputed and hoped over.

Per **account**, not per trade, because one trade has two sides belonging to two accounts. An
account's ledger is then a complete history of its own activity with no joins and no filtering.

## Marks: in memory, on purpose

`MarkPriceCache` is a `ConcurrentHashMap` of immutable `Mark` records. Not persisted, not in Redis.

A mark is pure derived state with a ~100 ms lifetime. Persisting it would mean thousands of writes a
second to store values obsolete before the transaction commits, and buy nothing — after a restart
the cache refills from the tick stream within one generation interval. Redis would be the same trade
plus a network hop.

**The consequence is stated rather than hidden:** for a moment after a restart this service reports
realised P&L and *no* unrealised P&L. That is why `null` means unknown.

The mark is the **mid of a two-sided quote**, falling back to the last trade — a mid is the price a
position could plausibly be closed at, whereas a last trade may be stale or a single print at an
outlier. Older sequence numbers are ignored, so out-of-order delivery across partitions cannot
rewind a mark.

## The exhaustive switch (Java 21)

Both listeners funnel into one dispatch point with **no `default` branch**:

```java
private void apply(DomainEvent event) {
    switch (event) {
        case TradeExecutedEvent trade -> positionService.applyTrade(trade);
        case MarketTickEvent tick     -> markPriceCache.update(tick);
        case OrderAcceptedEvent i        -> ignored(event);
        case OrderCancelRequestedEvent i -> ignored(event);
        case OrderCancelConfirmedEvent i -> ignored(event);
        case OrderLifecycleEvent i       -> ignored(event);
    }
}
```

This is the sealed hierarchy from Phase 1 finally doing work:

- The compiler verifies every case. Adding a seventh event type to `oms-common` makes **this class
  fail to compile** until somebody decides what position-service should do with it.
- The events this service deliberately ignores are *written down as ignoring them*. A
  `default -> {}` would silently absorb a new type, and the failure mode of an event nobody noticed
  is a position quietly wrong.

For a C++ reader: `std::variant` with a compiler-checked visitor, minus the visitor boilerplate. The
pre-21 alternative — an `instanceof` chain ending in `default -> throw` — moves an error the compiler
could have caught into production. Pattern matching for `switch` is why this project targets Java 21
([ADR 0006](adr/0006-target-java-21-not-17.md)).

## Two consumers, two groups, different policies

| | Trades | Ticks |
|---|---|---|
| Volume | tens/sec | thousands/sec |
| Delivery | ordered, transactional, effectively-once | conflatable, last-value-wins |
| Listener | record-at-a-time, `AckMode.RECORD` | **batched** (500), `AckMode.BATCH` |
| `auto-offset-reset` | `earliest` — positions are rebuilt from this topic | `latest` — a mark is a live value |
| Retry | exponential, 30 s budget | fixed, one attempt |
| Concurrency | 3 | 2 |

Separate containers in separate groups with separate offsets and thread pools, and **the tick
consumer is the one allowed to fall behind.** Sharing one container would let a tick backlog delay a
trade — which delays a position update, which delays a risk decision. That asymmetry is the design,
not an accident of configuration.

Applying one retry policy to both would be wrong in one direction or the other: either the money
path gives up too early, or the cache path blocks a partition for 30 seconds over a value the next
tick overwrites.

## API

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/v1/positions?openOnly=true` | defaults to open positions only |
| `GET` | `/api/v1/positions/{symbol}` | a never-traded symbol is a **flat position, not a 404** |
| `GET` | `/api/v1/pnl` | account summary, with a completeness flag |

```bash
curl -s -H 'X-Account-Id: ACC-TRADER-1' http://localhost:8084/api/v1/pnl
```

```json
{
  "accountId": "ACC-TRADER-1",
  "realisedPnl": 1000.0000,
  "unrealisedPnl": 2000.0000,
  "totalPnl": 3000.0000,
  "grossExposure": 87000.0000,
  "netExposure": 87000.0000,
  "openPositions": 2,
  "unmarkedSymbols": ["LUCK"],
  "complete": false,
  "asOf": "2026-09-29T09:15:00Z"
}
```

`complete: false` with `unmarkedSymbols: ["LUCK"]` says: the unrealised figure covers everything
*except* LUCK. Reporting the gap lets a caller decide whether to trust the number, instead of being
handed a figure that looks authoritative and is not.

## Tests

```bash
./mvnw -pl position-service -am test      # 49 tests, no Docker
./mvnw -pl position-service -am verify    # adds PositionFlowIT — needs Docker
```

| Test | What it protects |
|---|---|
| `PositionEntityTest` | 21 cases over opening, increasing, reducing, **crossing zero**, marks and guards. The most consequential test class in the platform — every other bug produces a wrong status; a bug here produces a wrong *number*, silently. |
| `PositionServiceTest` | Both sides in one transaction, ledger-key idempotency, an account trading with **itself** |
| `MarkPriceCacheTest` | Mid-not-last, out-of-order sequence rejection, absent-means-unknown |
| `PositionControllerTest` | That incompleteness is reported rather than silently summed |
| `PositionArchitectureTest` | No floating-point money, domain framework-free, no `@Transactional` on a listener |
| `PositionFlowIT` | Kafka → position → HTTP, the CHECK constraint, the append-only trigger, replay idempotency |
