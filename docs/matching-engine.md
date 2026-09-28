# matching-engine

A price-time priority limit order book held entirely in memory. The only stateful hot path in
the platform, and the one place where allocation and lock contention are treated as correctness
problems rather than performance nits.

Companion documents: [concurrency.md](concurrency.md) for the threading design,
[performance.md](performance.md) for the measurements,
[ADR 0005](adr/0005-single-writer-per-book-not-a-lock-free-order-book.md) for the decision.

## Package layout

```
com.oms.matching
├── book/          the data structure. No Spring, no I/O, no clock.
│   ├── LimitOrderBook       interface
│   ├── PriceTimeOrderBook   the book that ships
│   ├── NaiveOrderBook       the documented baseline (never wired in)
│   ├── PriceLevel           intrusive FIFO at one price   (package-private)
│   ├── OrderNode            a resting order, pooled       (package-private)
│   ├── NewOrder             what arrives
│   ├── MatchListener        where fills go
│   └── BookSnapshot         the immutable reader view
├── engine/        routing, the single-writer contract, deterministic trade ids
├── messaging/     Kafka in and out
├── api/           read-only depth endpoints
├── config/        typed properties, Kafka wiring
└── metrics/       Prometheus gauges, read from snapshots only
```

`book` is deliberately framework-free, and `EngineArchitectureTest` enforces it. That is what
lets JMH benchmark the book directly, with no container to start and nothing between the
benchmark and the code under test.

## Data structure

```
bids   TreeMap<Long, PriceLevel>  descending  →  firstEntry() is the best bid
asks   TreeMap<Long, PriceLevel>  ascending   →  firstEntry() is the best ask
index  HashMap<UUID, OrderNode>               →  O(1) cancel
pool   ArrayDeque<OrderNode>                  →  bounded free list
```

Each `PriceLevel` is an **intrusive** doubly-linked FIFO of `OrderNode`, plus a running
`totalQuantity`. Price priority comes from the tree ordering; time priority from the queue order
within a level.

Three choices carry the performance, in the order they matter:

**1. The `index` map.** A cancel is a hash lookup plus four pointer writes. Without it, a cancel
walks every price level and every order in each one — O(levels × orders). Cancels are the most
common message on a real venue, so this is the difference between a book that scales with depth
and one that does not. [performance.md](performance.md) measures it: the tuned book is flat from
200 to 2,000 resting orders per side; the baseline degrades with depth.

**2. Intrusive links.** `prev`/`next` live on the order itself, not in a wrapper node owned by a
collection. Removing from the middle of a queue is four pointer writes with no lookup and no
array shift, and there is one object per resting order rather than two. The same reason you reach
for `boost::intrusive::list` over `std::list` — and the win is larger here, because every extra
object is also extra collector work and an extra cache miss on the way to the data. The idiomatic
Java alternative, `ArrayDeque<Order>` per level, appends fine but removes from the front with an
O(n) shift on every complete fill.

**3. `long` ticks, never `BigDecimal`** (ADR 0002). Every price comparison in the inner loop is
two `long`s rather than a method call on a heap object wrapping a `BigInteger` wrapping an
`int[]`.

### Why a TreeMap and not a price ladder

The textbook high-performance answer is a flat array indexed by `(price − base) / tickSize`: O(1)
price lookup, perfect locality, no tree walk. It is faster and it is the right end state.

It is not built, for one honest reason: it needs a bounded, known price range per instrument, and
an order outside the window means either rejecting it or reallocating and rebasing the ladder.
That is a real design with real edge cases — a limit-up move is exactly when you do not want the
book to reallocate. Shipping a correct tree beats shipping a ladder that mishandles a circuit
breaker. It is recorded in [performance.md](performance.md) as the measured next step rather than
claimed as done.

What the tree costs is a red-black walk — O(log levels) — on insertion and on finding the best
price. With a few hundred live levels that is eight or nine comparisons. What it avoids is random
access per level during a sweep, because a sweep walks in order via `firstEntry()`.

## Matching algorithm

```
submit(order):
  limit  = order.effectiveLimitTicks()        # MARKET → MAX_VALUE / MIN_VALUE+1
  contra = order is BUY ? asks : bids

  if order is FOK and availableQuantity(contra, limit) < order.quantity:
      report FOK_UNFILLABLE for the full quantity; return      # decided before anything fills

  while remaining > 0 and contra not empty:
      (bestPrice, level) = contra.firstEntry()
      if not side.crosses(bestPrice, limit): break
      while remaining > 0 and level.head != null:
          fill = min(remaining, level.head.remaining)
          emit trade at bestPrice                              # the RESTING price
          decrement; unlink and recycle the node if it is exhausted
      remove the level if it emptied

  if remaining == 0:            return
  if order is MARKET or IOC:    report IOC_RESIDUAL for the remainder; return
  rest(order, remaining)
```

Details worth defending:

- **Trades print at the resting price.** The order that was there first set the price, and price
  improvement accrues to the aggressor. Standard continuous-book behaviour.
- **A market order is an infinitely aggressive limit** — `Long.MAX_VALUE` for a buy,
  `Long.MIN_VALUE + 1` for a sell. That removes the "is this a market order" branch from the inner
  loop entirely: the loop only ever compares two longs. `MIN_VALUE + 1` and not `MIN_VALUE`
  because `MIN_VALUE` is the `Ticks.NO_PRICE` sentinel, and overlapping the two would make an
  absent price indistinguishable from the most aggressive possible sell.
- **A market order never rests.** It has no price, so there is no price at which to rest it. The
  residual is cancelled.
- **FOK is decided before anything fills.** Match-then-roll-back is not available: you cannot
  un-emit a fill that has already been published. The feasibility check walks acceptable levels
  using per-level running totals and stops as soon as it has enough.
- **A cancel checks the account.** order-service has already scoped the request, but one
  comparison here means a bug upstream cannot cancel somebody else's order.

## Allocation on the hot path

Matching allocates **nothing**. The techniques, and what each avoids:

| Technique | Avoided |
|---|---|
| `MatchListener` takes primitives rather than returning `List<Fill>` | a list plus one object per fill, garbage immediately |
| `OrderNode` free list | one object per resting order, per quote/pull cycle |
| `long` ticks | a heap object per price comparison |
| `firstEntry()` + direct `remove`, no `Iterator` | an escaping iterator per level walked |
| no streams or lambdas in the loop | a pipeline object per stage, plus boxing |
| snapshot per **batch**, not per order | the only deliberate allocation, amortised over ~200 messages |

Left in the steady state: one `NewOrder` per message (already paid for by the Kafka
deserialiser), a `PriceLevel` the first time a price is touched, and one `BookSnapshot` per batch.
`PriceTimeOrderBookTest.steadyStateReusesNodes` proves the pool works — 500 quote/pull cycles
create exactly one node.

Why this matters more in Java than in C++: allocation itself is a pointer bump, but the bill
arrives later, on a thread you did not schedule, as a pause. A book that allocates per fill does
not read as slower matching — it reads as a worse p99 somewhere else, which is why it is so hard
to attribute after the fact.

## Concurrency, in one paragraph

One writer thread per book, guaranteed by the Kafka message key: symbol key → one partition →
one consumer thread → one writer. No lock, no atomic, no CAS in the matching path. Readers never
touch the book; the writer publishes an immutable `BookSnapshot` through a `volatile` field after
each batch, and readers load that reference. Parallelism is across symbols, strictly serial within
one. Full reasoning, including why a lock-free book would be the wrong tool, in
[concurrency.md](concurrency.md).

## Messaging

| Direction | Topic | Key | Notes |
|---|---|---|---|
| in | `oms.orders.accepted.v1` | symbol | batch listener |
| in | `oms.orders.cancel-requests.v1` | symbol | **same consumer group**, so a symbol's orders and cancels reach the same thread |
| out | `oms.trades.executed.v1` | symbol | one event carries both sides |
| out | `oms.orders.execution-reports.v1` | symbol | cancel confirmations, with a `CancelReason` |

The batch handler order is load-bearing:

```
match every record → await every broker ack → publish snapshots → return → Spring commits the offset
```

The engine has no database. Its durability story is "rebuild by replaying the partition", which
only works if the offset never advances past a batch whose trades did not reach the broker. A send
failure throws, the batch retries, and the retry re-emits **identical** trade ids.

## Recovery, and the class that makes it safe

```java
TradeIds.of(symbol, sequence)   // UUID.nameUUIDFromBytes(symbol + '\0' + sequence)
```

A restart or a partition reassignment loses the book; it is rebuilt from the earliest offset,
re-executing the same matches and re-emitting the same trades. That is safe only because the trade
id is a *function of* `(symbol, sequence)`, and the sequence is a deterministic function of the
input stream. order-service deduplicates fills on `(trade_id, order_id)`, so re-emission is a
no-op.

With `UUID.randomUUID()`, a rebuild would look like a stream of brand-new trades and **every
position in the platform would double**. The engine would appear correct and the money would be
wrong. Recovery design and id generation are one decision, not two.

Known limitation: replay time grows with retention — at 7 days, a cold start replays up to 7 days
of orders for its partitions. The production answer is periodic book snapshots to a compacted
topic. Not built; recorded in [performance.md](performance.md).

## API

Read-only. Every endpoint reads the published snapshot, so no HTTP request can take a lock, block
the writer, or observe a half-updated book.

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/v1/books` | symbols with a live book **on this instance** |
| `GET` | `/api/v1/books/{symbol}?depth=10` | depth of book, decimals not ticks |
| `GET` | `/api/v1/books/{symbol}/top` | top of book only |

```bash
curl -s http://localhost:8082/api/v1/books/HBL?depth=3
```

```json
{
  "symbol": "HBL", "sequence": 184213,
  "bestBid": 172.4400, "bestAsk": 172.4600, "spread": 0.0200,
  "bids": [{"price": 172.4400, "quantity": 5400, "orderCount": 7}],
  "asks": [{"price": 172.4600, "quantity": 3100, "orderCount": 4}]
}
```

Two documented properties rather than hidden surprises: the data is up to one batch stale, and an
instance only knows the symbols in its own partitions — asking the wrong instance returns an empty
book. An unknown symbol returns an empty book rather than 404, because a depth query must never
create engine state.

## Configuration

| Property | Default | Notes |
|---|---|---|
| `oms.engine.snapshot-depth` | 10 | price levels per side in a published snapshot |
| `oms.engine.dedupe-capacity` | 65536 | recently-seen order ids per book, **bounded** |
| `oms.engine.node-pool-limit` | 4096 | nodes retained for reuse; 0 disables pooling |
| `oms.engine.concurrency` | 3 | consumer threads = books matched in parallel, capped by partitions |
| `oms.engine.metrics-refresh-interval` | 5000 ms | gauge rebuild interval |

The dedupe window is bounded deliberately: an unbounded set of every order id ever seen is a
memory leak with a respectable name. The trade is that a duplicate delivered after more than
`dedupe-capacity` intervening orders would slip through — acceptable, because Kafka duplicates come
from a retry or a rebalance and are separated by a handful of records, not by 65,000.

## Metrics

| Metric | Type | Notes |
|---|---|---|
| `oms.engine.match` | timer | p50/p95/p99/p99.9 with a percentile histogram, not a mean |
| `oms.engine.book.levels` | gauge | per symbol, per side |
| `oms.engine.book.quantity` | gauge | resting quantity per side |
| `oms.engine.book.spread.ticks` | gauge | NaN on a one-sided book, so Prometheus leaves a gap |
| `oms.engine.books` | gauge | symbols on this instance |
| `oms.engine.trades.published` | counter | |
| `oms.engine.duplicates.ignored` | counter | should be near zero; a rising value means replays |

Every gauge reads the published snapshot, never the live book. A scrape is just another reader.

## Tests

```bash
./mvnw -pl matching-engine -am test      # 103 unit tests, no Docker
./mvnw -pl matching-engine -am verify    # adds MatchingEngineFlowIT — needs Docker
```

| Test | Kind | What it protects |
|---|---|---|
| `LimitOrderBookContractTest` | contract, run **twice** | Price and time priority, partial fills, market orders, IOC/FOK, cancels, sequencing, depth — against both implementations |
| `PriceTimeOrderBookTest` | unit | Pooling, empty-level removal, recycled-node cleanliness, wrong-account cancel |
| `BookInvariantTest` | randomised, seeded | 20,000 ops × 4 seeds: never crossed, and `submitted = 2×traded + cancelled + resting` exactly |
| `MatchingEngineTest` | unit | Replay suppression, book isolation, snapshot publication, race-free slot creation |
| `TradeIdsTest` | unit | Determinism — the property crash recovery depends on |
| `SnapshotPublicationConcurrencyTest` | concurrency | 1 writer + 4 readers, 1.5s: no torn snapshot, no blocking |
| `EngineArchitectureTest` | ArchUnit | No Spring or I/O in `book`, no JPA anywhere, no `BigDecimal` in the tuned book, baseline never wired in |
| `MatchingEngineFlowIT` | Testcontainers | Kafka in → trade out, deterministic id, depth over HTTP, cancel confirmation, replay suppression |

The contract test running against both books is what makes the performance comparison legitimate.
A faster implementation that fails the contract is not faster, it is wrong.
