package com.oms.marketdata.simulator;

import com.oms.common.event.MarketTickEvent;
import com.oms.common.marketdata.QuoteSnapshot;
import com.oms.common.money.Ticks;

import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The simulated state of one symbol: a mid price doing a mean-reverting random walk, a spread
 * around it, and the last traded print.
 *
 * <h2>Concurrency</h2>
 *
 * <p>Same shape as the matching engine (ADR 0005): <b>one writer</b> - the tick generator's
 * scheduler thread - and readers that only ever see an immutable snapshot published through a
 * {@code volatile} field.
 *
 * <p>There is a second potential writer, and it is handled rather than synchronised. The Kafka
 * listener that consumes executed trades needs to update the "last traded price", and it runs on
 * a different thread. Instead of locking the state, the listener drops the print into a
 * <b>single-slot conflating mailbox</b> ({@code pendingPrint}), and the generator applies it on
 * its next pass.
 *
 * <p>That is correct rather than merely convenient: "last traded price" is by definition
 * last-value-wins, so if two trades print between two ticks, the older one is genuinely
 * uninteresting. {@code AtomicReference.getAndSet(null)} makes the handover lock-free with one
 * producer and one consumer, and the single-writer discipline is preserved - the listener never
 * touches the walk state. A {@code synchronized} block would have worked and would have given a
 * Kafka consumer thread the ability to block tick generation, which is exactly the coupling the
 * design avoids everywhere else.
 */
public final class SymbolState {

    /** A trade print waiting to be folded into the quote. */
    public record TradePrint(long priceTicks, long quantity) {
    }

    private final String symbol;
    private final long referenceTicks;
    private final long tickSizeTicks;
    private final long spreadTicks;
    private final int maxStepTicks;
    private final long baseSize;

    /**
     * Seeded from the symbol, so a restart reproduces the same price path.
     *
     * <p>Deterministic on purpose: an integration test can assert on the feed, and two runs of a
     * demo look the same. A simulator that is different every time is much harder to reason about
     * than one that is boringly repeatable.
     */
    private final Random random;

    // --- writer-only state -----------------------------------------------------------
    private long midTicks;
    private long lastTradeTicks;
    private long lastTradeSize;
    private long sequence;

    /** Handover slot from the trade listener to the generator. Last print wins. */
    private final AtomicReference<TradePrint> pendingPrint = new AtomicReference<>();

    /** Published by the writer, read by anyone. Safe publication - see ADR 0005. */
    private volatile QuoteSnapshot snapshot;

    public SymbolState(String symbol, long referenceTicks, long tickSizeTicks,
                       long spreadTicks, int maxStepTicks, long baseSize) {
        this.symbol = symbol;
        this.referenceTicks = referenceTicks;
        this.tickSizeTicks = Math.max(1, tickSizeTicks);
        this.spreadTicks = Math.max(this.tickSizeTicks, spreadTicks);
        this.maxStepTicks = Math.max(1, maxStepTicks);
        this.baseSize = Math.max(1, baseSize);
        this.random = new Random(symbol.hashCode() * 31L + 17L);
        this.midTicks = referenceTicks;
        this.lastTradeTicks = referenceTicks;
        this.snapshot = emptySnapshot();
    }

    public String symbol() {
        return symbol;
    }

    /** Called from any thread. */
    public void offerTradePrint(long priceTicks, long quantity) {
        pendingPrint.set(new TradePrint(priceTicks, quantity));
    }

    /** Called from any thread. Never null. */
    public QuoteSnapshot currentSnapshot() {
        return snapshot;
    }

    public long sequence() {
        return sequence;
    }

    /**
     * Advances the walk one step and publishes a new snapshot. <b>Writer thread only.</b>
     *
     * <p>The walk is a random step with mean reversion toward the reference price. Without the
     * reversion term, an unbounded random walk eventually drifts far enough that every order is
     * rejected by the fat-finger band, and the demo stops working after twenty minutes. The
     * reversion keeps prices in a plausible range without pinning them.
     */
    public MarketTickEvent nextTick(Instant now) {
        TradePrint print = pendingPrint.getAndSet(null);
        if (print != null) {
            lastTradeTicks = print.priceTicks();
            lastTradeSize = print.quantity();
            // A real print drags the quote toward it: the market has spoken, and the simulated
            // mid should not ignore it.
            midTicks = (midTicks + print.priceTicks()) / 2;
        }

        int steps = random.nextInt(2 * maxStepTicks + 1) - maxStepTicks;
        long drift = steps * tickSizeTicks;

        // Mean reversion: pull back by a fraction of the distance from the reference.
        long deviation = midTicks - referenceTicks;
        long reversion = -deviation / 32;

        midTicks = alignToTick(midTicks + drift + reversion);

        // Never let the simulated mid collapse to or below zero.
        long floor = Math.max(tickSizeTicks, referenceTicks / 4);
        if (midTicks < floor) {
            midTicks = floor;
        }

        long halfSpread = alignToTick(Math.max(tickSizeTicks, spreadTicks / 2));
        long bid = Math.max(tickSizeTicks, midTicks - halfSpread);
        long ask = midTicks + halfSpread;

        long bidSize = baseSize * (1 + random.nextInt(10));
        long askSize = baseSize * (1 + random.nextInt(10));

        sequence++;

        MarketTickEvent tick = new MarketTickEvent(
                UUID.randomUUID().toString(),
                now,
                MarketTickEvent.CURRENT_SCHEMA_VERSION,
                symbol,
                bid, bidSize,
                ask, askSize,
                lastTradeTicks, lastTradeSize,
                sequence);

        // Publish for readers before returning, so a subscriber that receives the tick and then
        // immediately queries the snapshot cannot see an older one.
        this.snapshot = new QuoteSnapshot(symbol,
                Ticks.toDecimal(bid), bidSize,
                Ticks.toDecimal(ask), askSize,
                Ticks.toDecimal(lastTradeTicks), lastTradeSize,
                sequence, now);

        return tick;
    }

    private long alignToTick(long priceTicks) {
        long remainder = priceTicks % tickSizeTicks;
        return remainder == 0 ? priceTicks : priceTicks - remainder;
    }

    private QuoteSnapshot emptySnapshot() {
        return new QuoteSnapshot(symbol,
                null, 0L, null, 0L,
                Ticks.toDecimal(referenceTicks), 0L,
                0L, Instant.EPOCH);
    }
}
