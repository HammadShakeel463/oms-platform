# Order book performance: measure, fix, re-measure

A real tuning pass on the matching engine, with the numbers that came out of it — including one
result that refuted the hypothesis I started with.

Everything here is reproducible: see [§8](#8-reproducing-this).

---

## 1. Test environment

| | |
|---|---|
| CPU | Intel Xeon E5-1650 v3 @ 3.50 GHz, 6 cores / 12 threads |
| RAM | 12 GB |
| OS | Windows 11 (26100) |
| JDK | 25.0.4.1, HotSpot 64-bit Server VM (compiling to `--release 17`) |
| JMH | 1.37, 2 forks, 5×1s warmup, 6–8×1s measurement |
| JVM args | `-Xms1g -Xmx1g -XX:+AlwaysPreTouch -XX:+UseSerialGC` |
| Profiler | `-prof gc` for allocation rate |

**Four caveats up front, because the numbers are worth less without them.**

1. **This is a developer workstation running Windows, not a tuned Linux box.** No CPU pinning, no
   isolated cores, no `tickless` kernel, turbo and C-states active. The maxima in the tables below
   (400 µs – 8 ms) are OS scheduling and GC pauses, not the book. Treat the medians and the ratios
   as meaningful and the absolute tail beyond p99.9 as an artefact of the host.
2. **`System.nanoTime()` on Windows has ~100 ns granularity.** Every latency at or below 100 ns is
   at the limit of the timer, which is why the TUNED percentiles land on suspiciously round
   figures (100 / 200 / 300 ns). Read TUNED p50–p95 as "≤ 100 ns, below what this clock can
   resolve", not as a measurement.
3. **SerialGC is deliberate and is not what production runs.** Allocation rate is one of the things
   being compared, and a concurrent collector does its work on other threads where wall-clock
   throughput cannot see it. Serial GC puts that cost back in front of the mutator. It also
   happens to be the *most* favourable case for cheap young-generation collection, which matters
   for the negative result in §5.
4. **JDK 25, not JDK 21.** Boot 3.5's supported ceiling is lower; JDK 25 is what is installed on
   this machine. The relative comparisons are unaffected — every variant runs on the same JVM — but
   the absolute figures should be re-taken on the JDK the service will actually be deployed on.

---

## 2. What was measured, and against what

Three variants of the same contract, all passing the same 27 `LimitOrderBookContractTest` cases:

| Variant | Description |
|---|---|
| **NAIVE** | `NaiveOrderBook` — the baseline. `BigDecimal` prices, `TreeMap<BigDecimal, ArrayList<Order>>`, no id index (linear-scan cancel), a `List<Fill>` allocated per order, a stream for the FOK check. Every choice is the idiomatic, readable one. |
| **TUNED** | `PriceTimeOrderBook` — `long` ticks, `TreeMap<Long, PriceLevel>`, intrusive FIFO levels, `HashMap<UUID, OrderNode>` cancel index, bounded node pool, primitives pushed to a listener instead of a fill list. |
| **TUNED_UNPOOLED** | `PriceTimeOrderBook` with `nodePoolLimit = 0`. Identical in every other respect, so the difference isolates exactly one change. |

Keeping the baseline in the repository is what makes the comparison checkable rather than
asserted, and running the contract test against both is what makes it *fair* — a faster book that
fails the contract is not faster, it is wrong.

Two workloads:

**`QuotePullBenchmark`** — post an order, then cancel it, against a book of fixed depth. A genuine
steady state: every operation adds exactly one order and removes exactly one, so the book stays at
its pre-loaded depth for the whole run. This is the most representative operation on a real venue
— market makers quote and pull continuously, and the large majority of messages never trade — and
it is the workload whose numbers I would defend.

**`MatchingThroughputBenchmark` / `MatchingLatencyBenchmark`** — a mixed stream: 45% passive
limits, 25% cancels, 25% aggressive limits, 5% market orders.
**This workload is net-accumulating** (45% rest against 25% cancel), so the book grows during an
iteration — by the end of a one-second iteration the TUNED book holds on the order of tens of
thousands of resting orders. That is a real scenario (a quiet symbol building depth through a
session) but it is not a steady state, and it inflates the gap between the two implementations
because the baseline's costs are the ones that scale with depth. It is reported below, with that
caveat attached, and it is not the headline.

---

## 3. Steady state: quote and pull

Throughput, higher is better:

| Variant | 200 resting/side | 2,000 resting/side | Change with 10× depth |
|---|---|---|---|
| **TUNED** | **13.83 ± 1.39 ops/µs** | **13.28 ± 1.03 ops/µs** | **−4%** |
| TUNED_UNPOOLED | 14.44 ± 0.96 ops/µs | 13.88 ± 0.91 ops/µs | −4% |
| NAIVE | 8.81 ± 0.32 ops/µs | 1.92 ± 0.19 ops/µs | **−78%** |

Latency, `SampleTime`, one post-and-pull cycle:

| Variant | depth | mean | p50 | p99 | p99.9 |
|---|---|---|---|---|---|
| TUNED | 200 | 0.098 µs | ≤0.1 µs | 0.30 µs | 1.85 µs |
| TUNED | 2,000 | 0.107 µs | ≤0.1 µs | 0.30 µs | — |
| NAIVE | 200 | 0.143 µs | ≤0.1 µs | 0.40 µs | 3.83 µs |
| NAIVE | 2,000 | **0.627 µs** | 0.30 µs | **2.30 µs** | **34.24 µs** |

**At 2,000 resting orders per side: 5.9× on the mean, 7.7× at p99, 18× at p99.9.**

The important number in this section is not any of those ratios — it is **−4% versus −78%**. The
tuned book is *flat* across a tenfold increase in depth; the baseline loses three quarters of its
throughput. That is the shape of O(1) against O(n), and it is the whole argument for the
`HashMap<UUID, OrderNode>` index. A single-point comparison could be explained away as constant
factors; a slope cannot.

---

## 4. Mixed workload, accumulating book

Throughput:

| Variant | 200 resting/side | 2,000 resting/side |
|---|---|---|
| **TUNED** | **9,285,999 ± 486,137 ops/s** | **8,895,189 ± 578,580 ops/s** |
| TUNED_UNPOOLED | 9,395,007 ± 610,151 ops/s | 8,747,738 ± 658,767 ops/s |
| NAIVE | 84,854 ± 1,927 ops/s | 74,359 ± 2,371 ops/s |

**≈109× at depth 200, ≈120× at depth 2,000.** With the accumulation caveat from §2 firmly
attached: by the end of an iteration the baseline is cancelling against a book of tens of
thousands of orders, so this figure includes the compounding effect of an O(n) cancel against a
growing n. It is a legitimate measurement of a legitimate scenario, and it is not the number to
quote as "the book is 100× faster".

Latency on the same workload is where the baseline gets interesting:

| Variant | depth | mean | p50 | p90 | p95 | p99 | p99.9 |
|---|---|---|---|---|---|---|---|
| TUNED | 200 | 173 ns | ≤100 ns | 200 ns | 300 ns | **500 ns** | 2.5 µs |
| TUNED | 2,000 | 181 ns | ≤100 ns | 200 ns | 300 ns | **500 ns** | 2.6 µs |
| NAIVE | 200 | 11,139 ns | 200 ns | 43.8 µs | 87.4 µs | **131 µs** | 151 µs |
| NAIVE | 2,000 | 12,515 ns | 300 ns | 50.0 µs | 90.9 µs | **131 µs** | 172 µs |

**The baseline's mean is 11.1 µs and its median is 200 ns — a factor of 55.** That is the cleanest
illustration in this whole document of why a mean latency is close to useless: 75% of operations in
this mix are cheap (a passive insert or an aggressive order hitting the touch), and the 25% that
are cancels cost tens of microseconds. The average describes neither population. Anyone reporting
"average matching latency" for this book would be reporting a number that no individual operation
ever experiences.

The tuned book's distribution is the shape you want: p50 at the clock floor, p99 at 500 ns, and
the first real excursion at p99.9 (2.5 µs), which is a young-generation collection.

---

## 5. The negative result: node pooling did not do what I expected

The hypothesis going in was that pooling `OrderNode` objects would improve throughput and tail
latency by removing per-order allocation. **The measurement does not support that.**

| Metric (mixed workload, depth 200) | TUNED (pooled) | TUNED_UNPOOLED | Verdict |
|---|---|---|---|
| Throughput | 9,285,999 ± 486,137 ops/s | 9,395,007 ± 610,151 ops/s | **no difference** (unpooled nominally higher, well inside the error) |
| Allocation | **47.3 B/op** | 67.2 B/op | **−30% with pooling** |
| p99 latency | 500 ns | 400 ns | no difference (unpooled nominally better) |
| p99.9 latency | 2.5 µs | 1.40 µs | no difference worth claiming |

Same picture on quote/pull: 121.3 B/op pooled against 177.3 B/op unpooled — a clean 56 bytes
saved, exactly one `OrderNode` — and throughput identical at 13.83 against 14.44 ops/µs.

**Why the hypothesis was wrong.** The generational hypothesis holds almost perfectly here: a
pooled node's whole purpose is to avoid allocating an object that would die within microseconds. A
young-generation copying collector only touches *survivors* — dead objects cost nothing to
collect, the space is reclaimed by moving the pointer back. So removing an allocation that was
going to die young removes almost no work. The 30% allocation reduction is real; the work it
avoids is nearly zero under this collector and this heap.

**What I did about it.** The mechanism stays, configurable via `oms.engine.node-pool-limit` and
covered by tests (`steadyStateReusesNodes` proves 500 quote/pull cycles create exactly one node).
But this document says plainly that **the benchmark does not justify it**, because the alternative
— quietly keeping a hypothesis that the data refuted — is the thing that makes performance work
untrustworthy.

The conditions under which it *would* pay, and which are the next measurement rather than an
assumption:

- **A shared production heap.** The benchmark measures the book alone. In the running service the
  same heap is being allocated into by the Kafka client, the JSON deserialiser and the HTTP layer;
  a 30% lower allocation rate from the engine means proportionally fewer young collections for
  everyone.
- **A different collector.** Under ZGC or Shenandoah, allocation rate translates into concurrent
  GC CPU rather than into stop-the-world pauses, and the arithmetic changes.
- **A larger live set.** If the book held enough long-lived depth that nodes started being
  promoted, they would stop being free.

If a measurement under G1 with the full service running shows nothing either, the pool should be
deleted. That is a deliberate follow-up, recorded here so it does not get lost.

---

## 6. Allocation, by variant

`gc.alloc.rate.norm` — bytes allocated per operation, from the JMH GC profiler:

| Workload | TUNED | TUNED_UNPOOLED | NAIVE |
|---|---|---|---|
| Mixed, depth 200 | **47.3 B/op** | 67.2 B/op | 163.9 B/op |
| Mixed, depth 2,000 | **48.1 B/op** | 68.3 B/op | 200.3 B/op |
| Quote/pull, depth 200 | **121.3 B/op** | 177.3 B/op | 311.1 B/op |
| Quote/pull, depth 2,000 | **121.4 B/op** | 177.3 B/op | 383.0 B/op |

Two things to read out of this table:

**The tuned book allocates 3.5× less than the baseline, and its allocation is depth-independent**
(47.3 → 48.1 B/op across a tenfold depth change). The baseline's *grows* with depth (163.9 → 200.3,
311 → 383), because its per-level `ArrayList` reallocation and its per-order fill list both scale
with how much of the book an operation touches. An allocation rate that scales with book depth is
a much worse property than a high constant one.

**47 B/op is not zero, and the matching path is not the source of it.** What remains is the
`NewOrder` record per incoming message — which the Kafka deserialiser has already paid for in the
running service, and which the benchmark charges to the book because it is replaying a
pre-generated stream — plus a `PriceLevel` on first touch of a price and one `BookSnapshot` per
batch. The claim in [concurrency.md](concurrency.md) is that *matching* allocates nothing, and
these figures are consistent with it.

---

## 7. What the pass actually established

The four hypotheses I started with, and what the measurements say about each:

| Fix | Hypothesis | Verdict |
|---|---|---|
| `HashMap<UUID, OrderNode>` cancel index | Dominant, because cancels dominate real traffic | **Confirmed, and it is the big one.** −4% vs −78% throughput across a 10× depth change (§3). The only fix whose effect is visible as a slope rather than a constant. |
| Intrusive FIFO instead of `ArrayList` per level | Removes an O(n) array shift per complete fill | **Confirmed as part of the above**, not isolated — see the gap below. |
| `long` ticks instead of `BigDecimal` | Removes a heap object per price comparison | **Confirmed as part of the above**, not isolated — see the gap below. |
| `OrderNode` pooling | Improves throughput and tail latency | **Refuted** on throughput and latency; confirmed only as a −30% allocation reduction (§5). |

**The honest gap: I cannot separate the `BigDecimal` cost from the `ArrayList` cost with the
variants that exist.** NAIVE changes four things at once, so §3 and §4 measure their combined
effect. The experiment that would settle it is a fourth variant — `PriceTimeOrderBook` with
`TreeMap<BigDecimal, PriceLevel>` keys and everything else unchanged — which isolates the price
representation on its own. That is one class and one `@Param` value, and it is the next thing I
would run. Saying "BigDecimal was 20% of it" without that variant would be making a number up.

### The next optimisation, and why it is not done

A **flat array price ladder** indexed by `(price − base) / tickSize`, replacing the `TreeMap`:
O(1) best-price lookup and insertion instead of O(log levels), and contiguous memory instead of a
pointer chase per node.

It is not built because it needs a bounded, known price range per instrument, and an order outside
the window means either rejecting it or reallocating and rebasing the ladder — and a limit-up move
is precisely when you do not want the book to reallocate. A correct tree beats a ladder that
mishandles a circuit breaker. With the tuned book at ~13 ops/µs in steady state and 500 ns at p99,
the tree is not the bottleneck at any volume this platform will see.

Also open, from [concurrency.md](concurrency.md): periodic book snapshots to a compacted topic, so
a cold start replays the tail rather than up to seven days of orders. That is a *recovery-time*
optimisation, and at the moment it is the weakest number in the engine — far weaker than anything
on this page.

---

## 8. Reproducing this

```bash
./mvnw -pl matching-bench -am package -DskipTests
```

```bash
java -jar matching-bench/target/benchmarks.jar -f 2 -prof gc -rf json -rff jmh-result.json
```

Roughly 11 minutes for the full matrix. Narrower runs:

```bash
java -jar matching-bench/target/benchmarks.jar QuotePullBenchmark -f 2 -prof gc
```

```bash
java -jar matching-bench/target/benchmarks.jar -p implementation=TUNED -p restingPerSide=2000 -f 1
```

The workload is seeded (`Workload.WORKLOAD_SEED`), so two runs replay an identical stream and a
before/after comparison is like-for-like.

---

## 9. Summary for an interview

1. **The headline is a slope, not a ratio.** The tuned book loses 4% of its throughput when depth
   goes up tenfold; the baseline loses 78%. That is the O(1) cancel index, and it is the fix that
   mattered — a single-point speedup can be argued about, a difference in scaling cannot.
2. **In steady state it is 5.9× on the mean and 7.7× at p99**; on an accumulating book the gap
   reaches ~109×, and I say which of those two numbers I would defend and why.
3. **The baseline's mean latency is 55× its median** — the single best argument I have for why a
   trading system is specified in percentiles and not averages.
4. **One hypothesis was refuted by its own benchmark.** Node pooling cut allocation 30% and did
   nothing measurable to throughput or latency, because a young-generation copying collector
   reclaims dead objects for free. I kept the mechanism, documented that the data does not justify
   it, and named the follow-up measurement that would settle it.
5. **I know what I did not measure**: `BigDecimal` and `ArrayList` are not separated, the host is a
   Windows workstation with a ~100 ns clock, the collector is not the production one, and the
   ladder optimisation is deliberately not built. All four are written down rather than left for a
   reviewer to find.
