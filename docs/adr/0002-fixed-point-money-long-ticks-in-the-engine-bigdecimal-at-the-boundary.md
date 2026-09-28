# ADR 0002 — Fixed-point money: `long` ticks in the engine, `BigDecimal` at the boundary

- **Status:** Accepted
- **Date:** 2026-09-28
- **Phase:** 1

## Context

Prices and notionals appear in four places with incompatible requirements:

| Place | Requirement |
|---|---|
| JSON over REST | Unambiguous decimal. A client sending `172.45` must get back `172.4500`, not `172.44999999999999`. |
| PostgreSQL | Exact, indexable, comparable. Auditors and reporting tools expect `NUMERIC`. |
| Risk checks | Exact, and overflow must be detectable. |
| Matching engine hot path | No allocation, no virtual dispatch, cache-friendly comparison. Called millions of times. |

`BigDecimal` satisfies the first three and fails the fourth badly. It is a heap object
wrapping a `BigInteger` (itself an `int[]`), every arithmetic operation allocates a new one,
`compareTo` is a method call that may need to rescale, and a price comparison in the inner
matching loop therefore costs a pointer chase plus an allocation. In an order book that
compares prices on every insertion, that is not a micro-optimisation: it is sustained
allocation pressure feeding young-generation GC, and GC pauses land directly in the p99 of
order acknowledgement.

`double` satisfies the fourth and fails the first three. `0.1 + 0.2 != 0.3` is not a
theoretical concern when the number is a client's limit price.

## Decision

Represent price as **`long` tick counts at a fixed scale of 4 decimal places** inside the
matching engine and in every Kafka event. Represent it as **`BigDecimal`** in REST payloads
and in PostgreSQL (`NUMERIC(18,4)`).

[`Ticks`](../../oms-common/src/main/java/com/oms/common/money/Ticks.java) is the single
conversion boundary:

- `Ticks.SCALE = 4`, `Ticks.ONE = 10_000`
- `fromDecimal(BigDecimal)` — converts, using `RoundingMode.UNNECESSARY`, so a price with
  more precision than the scale can hold throws rather than rounding silently
- `toDecimal(long)` — converts back, exactly
- `NO_PRICE = Long.MIN_VALUE` — the sentinel for "no price", i.e. a market order or an empty
  book side
- `notionalTicks(qty, price)` — uses `Math.multiplyExact`
- `format(long)` — string formatting for logs without building a `BigDecimal`

Quantity is a plain `long` of shares, always positive; direction lives in `Side`, whose
`sign()` returns ±1 so position arithmetic stays branch-free.

`double` is used for money nowhere. Not in a DTO, not in a test, not in a log line.

## Alternatives considered

**`BigDecimal` everywhere.** Simplest and safest, and correct for a system whose bottleneck is
the database. Rejected because the matching engine is the part of this project meant to
demonstrate that latency work was considered, and `BigDecimal` in the inner loop is the single
largest avoidable source of allocation there. Phase 3 quantifies the difference rather than
asserting it.

**`long` everywhere, including the API.** Rejected at the API boundary. Exposing
`limitPriceTicks: 1724500` makes the caller responsible for knowing the scale, and the first
client that hardcodes the wrong exponent sends an order a hundred times too large. The scale
is an internal representation detail and should stay internal.

**A `Price` value class wrapping a `long`.** Type safety without the primitive's ambiguity —
you cannot pass a quantity where a price is wanted. This is the right answer and it is what
Project Valhalla's value classes will eventually make free. Rejected for now because in Java
17 it is a heap-allocated object unless escape analysis happens to scalarise it, and "happens
to" is not a guarantee you can build a latency budget on. The C++ equivalent — a
zero-overhead `struct Price { int64_t ticks; }` — genuinely costs nothing; the Java version
does not, yet. Revisit when the project moves to a JDK with value classes.

**Scale of 2 instead of 4.** Rejected: 2dp cannot represent sub-paisa tick sizes, and 4dp
still leaves headroom to ~922 trillion in notional before a `long` overflows.

## Consequences

**Good**

- The engine's inner loop compares two `long`s. No allocation, no dispatch, and the values
  sit inline in arrays and object headers rather than behind two pointer hops.
- Overflow is a loud `ArithmeticException` rather than a silently negative notional that
  slips past a `notional < limit` risk check. This is the one that would actually cost money:
  a fat-finger quantity multiplied into a wrapped negative number passes every upper-bound
  check ever written.
- Exact decimal arithmetic survives at both ends: the client sees decimals, the database
  stores decimals, and the conversion between them is lossless in both directions.

**Costs, accepted**

- Two representations means a conversion boundary, and a conversion boundary is a place to
  get it wrong. Mitigated by keeping conversion in exactly one class, with
  `TicksTest` covering round-trips, the sentinel, overflow and excess precision.
- Reading `1_724_500` in a log is worse than reading `172.45`. Hence `Ticks.format`, used in
  every log statement on the hot path.
- A 4dp scale is a hard ceiling. An instrument needing 6dp would require a migration of the
  scale constant, the database columns and the event contract.

## Notes for the C++ reader

The representation choice is the same one you would make — integral ticks in the core, decimal
at the edges. Two Java-specific differences are worth holding onto.

First, `BigDecimal`'s cost is not merely "slower arithmetic"; it is *allocation*, and
allocation in Java has a delayed cost paid by the collector at a time you do not choose. A
profile that shows `BigDecimal.add` at 2% of CPU can still be the reason your p99 is bad,
because the real cost shows up as GC pauses elsewhere. Reasoning about total allocation rate
matters more here than in C++, where `new` costs what it costs, where you called it.

Second, Java has no `int128`, no `unsigned`, and no `__builtin_mul_overflow`. The `Math.*Exact`
family is the replacement, and unlike the builtins it throws rather than returning a flag —
so overflow handling is an exception path, not a branch. On the hot path that is fine, because
the exception is genuinely exceptional.
