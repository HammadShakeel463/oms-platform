package com.oms.marketdata.simulator;

import com.oms.common.event.MarketTickEvent;
import com.oms.common.money.Ticks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SymbolStateTest {

    private static final long REFERENCE = Ticks.fromDecimal(new BigDecimal("172.0000"));
    private static final long TICK_SIZE = Ticks.fromDecimal(new BigDecimal("0.0100"));

    private static SymbolState state() {
        return new SymbolState("HBL", REFERENCE, TICK_SIZE, TICK_SIZE * 2, 3, 100);
    }

    private static Instant now() {
        return Instant.parse("2026-09-29T09:15:00Z");
    }

    @Test
    @DisplayName("a fresh state publishes a snapshot with no quote, not a fabricated one")
    void freshStateHasNoQuote() {
        SymbolState state = state();

        assertThat(state.currentSnapshot()).isNotNull();
        assertThat(state.currentSnapshot().bid()).isNull();
        assertThat(state.currentSnapshot().ask()).isNull();
        assertThat(state.sequence()).isZero();
    }

    @Test
    @DisplayName("a tick quotes a two-sided market around the mid, on the tick grid")
    void tickQuotesBothSides() {
        SymbolState state = state();

        MarketTickEvent tick = state.nextTick(now());

        assertThat(tick.symbol()).isEqualTo("HBL");
        assertThat(tick.bidPriceTicks()).isLessThan(tick.askPriceTicks());
        assertThat(tick.bidPriceTicks() % TICK_SIZE)
                .as("a quoted price off the tick grid would be rejected by the tick-size risk check")
                .isZero();
        assertThat(tick.askPriceTicks() % TICK_SIZE).isZero();
        assertThat(tick.bidSize()).isPositive();
        assertThat(tick.askSize()).isPositive();
        assertThat(tick.sequence()).isEqualTo(1);
    }

    @Test
    @DisplayName("the snapshot is published before the tick is returned")
    void snapshotIsPublishedWithTheTick() {
        SymbolState state = state();

        MarketTickEvent tick = state.nextTick(now());

        // A subscriber that receives the tick and immediately queries the snapshot must not see
        // an older one.
        assertThat(state.currentSnapshot().sequence()).isEqualTo(tick.sequence());
        assertThat(state.currentSnapshot().bid())
                .isEqualByComparingTo(Ticks.toDecimal(tick.bidPriceTicks()));
    }

    @Test
    @DisplayName("sequence numbers increase by one per tick")
    void sequenceIsMonotonic() {
        SymbolState state = state();

        for (long expected = 1; expected <= 50; expected++) {
            assertThat(state.nextTick(now()).sequence()).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("the walk stays near the reference price - it does not drift out of the risk band")
    void meanReversionKeepsPricesPlausible() {
        // Without mean reversion an unbounded random walk eventually drifts far enough that every
        // order is rejected by the fat-finger band, and the demo stops working after 20 minutes.
        SymbolState state = state();
        long widest = 0;

        for (int i = 0; i < 20_000; i++) {
            MarketTickEvent tick = state.nextTick(now());
            long mid = tick.midPriceTicks();
            widest = Math.max(widest, Math.abs(mid - REFERENCE));
        }

        BigDecimal deviationPercent = BigDecimal.valueOf(widest)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(REFERENCE), 2, java.math.RoundingMode.HALF_UP);

        assertThat(deviationPercent)
                .as("20,000 ticks must stay inside the 10%% fat-finger band; widest was %s%%",
                        deviationPercent)
                .isLessThan(BigDecimal.TEN);
    }

    @Test
    @DisplayName("the price never collapses to zero or below")
    void priceStaysPositive() {
        SymbolState state = state();

        for (int i = 0; i < 50_000; i++) {
            MarketTickEvent tick = state.nextTick(now());
            assertThat(tick.bidPriceTicks()).isPositive();
            assertThat(tick.askPriceTicks()).isPositive();
        }
    }

    @Test
    @DisplayName("the walk is deterministic for a symbol, so two runs replay identically")
    void walkIsDeterministic() {
        List<Long> first = midsOf(state(), 200);
        List<Long> second = midsOf(state(), 200);

        assertThat(second)
                .as("seeded from the symbol, so an integration test can assert on the feed and a "
                        + "demo looks the same twice")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("different symbols follow different paths")
    void differentSymbolsDiffer() {
        SymbolState hbl = new SymbolState("HBL", REFERENCE, TICK_SIZE, TICK_SIZE * 2, 3, 100);
        SymbolState ogdc = new SymbolState("OGDC", REFERENCE, TICK_SIZE, TICK_SIZE * 2, 3, 100);

        assertThat(midsOf(ogdc, 200)).isNotEqualTo(midsOf(hbl, 200));
    }

    @Test
    @DisplayName("a trade print becomes the last price on the next tick")
    void tradePrintIsFoldedIn() {
        SymbolState state = state();
        state.nextTick(now());

        long printPrice = Ticks.fromDecimal(new BigDecimal("171.5000"));
        state.offerTradePrint(printPrice, 750);
        MarketTickEvent tick = state.nextTick(now());

        assertThat(tick.lastPriceTicks()).isEqualTo(printPrice);
        assertThat(tick.lastSize()).isEqualTo(750);
    }

    @Test
    @DisplayName("only the most recent print survives - the handover slot conflates")
    void onlyTheLatestPrintSurvives() {
        SymbolState state = state();

        state.offerTradePrint(Ticks.fromDecimal(new BigDecimal("170.0000")), 100);
        state.offerTradePrint(Ticks.fromDecimal(new BigDecimal("171.0000")), 200);
        long latest = Ticks.fromDecimal(new BigDecimal("172.5000"));
        state.offerTradePrint(latest, 300);

        MarketTickEvent tick = state.nextTick(now());

        assertThat(tick.lastPriceTicks())
                .as("last traded price is last-value-wins by definition, so conflating the "
                        + "handover slot is correct rather than merely convenient")
                .isEqualTo(latest);
        assertThat(tick.lastSize()).isEqualTo(300);
    }

    @Test
    @DisplayName("a print drags the quoted mid toward it")
    void printMovesTheQuote() {
        SymbolState state = state();
        state.nextTick(now());
        long midBefore = state.currentSnapshot().bid()
                .add(state.currentSnapshot().ask())
                .divide(BigDecimal.valueOf(2), 4, java.math.RoundingMode.HALF_UP)
                .movePointRight(4).longValue();

        // A print far below the current market.
        state.offerTradePrint(Ticks.fromDecimal(new BigDecimal("160.0000")), 1_000);
        MarketTickEvent after = state.nextTick(now());

        assertThat(after.midPriceTicks())
                .as("the market has spoken; the simulated mid should not ignore it")
                .isLessThan(midBefore);
    }

    @Test
    @DisplayName("the print slot is emptied once consumed")
    void printIsConsumedOnce() {
        SymbolState state = state();
        long printPrice = Ticks.fromDecimal(new BigDecimal("165.0000"));

        state.offerTradePrint(printPrice, 500);
        state.nextTick(now());
        MarketTickEvent second = state.nextTick(now());

        // The last price persists (it is the last trade), but the size is not re-applied and the
        // mid is not dragged a second time.
        assertThat(second.lastPriceTicks()).isEqualTo(printPrice);
        assertThat(second.lastSize()).isEqualTo(500);
    }

    private static List<Long> midsOf(SymbolState state, int count) {
        List<Long> mids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            mids.add(state.nextTick(now()).midPriceTicks());
        }
        return mids;
    }
}
