package com.oms.order.risk;

import com.oms.common.domain.Side;
import com.oms.common.error.RiskRejectedException;
import com.oms.common.reference.InstrumentStatus;
import com.oms.order.TestFixtures;
import com.oms.order.domain.OrderEntity;
import com.oms.order.domain.PositionExposureEntity;
import com.oms.order.risk.checks.LotSizeCheck;
import com.oms.order.risk.checks.MaxOrderValueCheck;
import com.oms.order.risk.checks.MaxPositionCheck;
import com.oms.order.risk.checks.PriceBandCheck;
import com.oms.order.risk.checks.TickSizeCheck;
import com.oms.order.risk.checks.TradeableInstrumentCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every pre-trade check, tested as a pure function.
 *
 * <p>No Mockito, no Spring context, no database - which is the payoff of passing a
 * {@link RiskContext} instead of letting each check fetch what it needs. These run in
 * milliseconds and they are the tests that actually protect money.
 */
class RiskChecksTest {

    private static RiskContext context(OrderEntity order, long effectivePriceTicks) {
        return new RiskContext(order, TestFixtures.instrument(), TestFixtures.account(),
                TestFixtures.exposure(), effectivePriceTicks);
    }

    @Nested
    @DisplayName("Instrument tradeable")
    class Tradeable {

        private final TradeableInstrumentCheck check = new TradeableInstrumentCheck();

        @Test
        void activeInstrumentPasses() {
            assertThatCode(() -> check.check(context(
                    TestFixtures.limitOrder(Side.BUY, 100, "172.4500"), 1_724_500L)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a halted instrument is rejected")
        void haltedIsRejected() {
            var ctx = new RiskContext(
                    TestFixtures.limitOrder(Side.BUY, 100, "172.4500"),
                    TestFixtures.instrument(InstrumentStatus.HALTED),
                    TestFixtures.account(), TestFixtures.exposure(), 1_724_500L);

            assertThatThrownBy(() -> check.check(ctx))
                    .isInstanceOf(RiskRejectedException.class)
                    .hasMessageContaining("HALTED");
        }
    }

    @Nested
    @DisplayName("Lot size")
    class LotSize {

        private final LotSizeCheck check = new LotSizeCheck();

        @Test
        void quantityOnTheLotGridPasses() {
            var ctx = new RiskContext(TestFixtures.limitOrder(Side.BUY, 500, "172.4500"),
                    TestFixtures.instrument("172.4500", new BigDecimal("10.00"), 100L, "0.0100"),
                    TestFixtures.account(), TestFixtures.exposure(), 1_724_500L);

            assertThatCode(() -> check.check(ctx)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an odd lot is rejected")
        void oddLotIsRejected() {
            var ctx = new RiskContext(TestFixtures.limitOrder(Side.BUY, 550, "172.4500"),
                    TestFixtures.instrument("172.4500", new BigDecimal("10.00"), 100L, "0.0100"),
                    TestFixtures.account(), TestFixtures.exposure(), 1_724_500L);

            assertThatThrownBy(() -> check.check(ctx))
                    .isInstanceOf(RiskRejectedException.class)
                    .hasMessageContaining("lot size");
        }
    }

    @Nested
    @DisplayName("Tick size")
    class TickSize {

        private final TickSizeCheck check = new TickSizeCheck();

        @Test
        void priceOnTheTickGridPasses() {
            assertThatCode(() -> check.check(context(
                    TestFixtures.limitOrder(Side.BUY, 100, "172.4500"), 1_724_500L)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a sub-tick price is rejected")
        void subTickPriceIsRejected() {
            assertThatThrownBy(() -> check.check(context(
                    TestFixtures.limitOrder(Side.BUY, 100, "172.4567"), 1_724_567L)))
                    .isInstanceOf(RiskRejectedException.class)
                    .hasMessageContaining("tick size");
        }

        @Test
        @DisplayName("a market order has no price and is not checked")
        void marketOrderSkipped() {
            assertThatCode(() -> check.check(context(
                    TestFixtures.marketOrder(Side.BUY, 100), 1_810_725L)))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Fat-finger price band")
    class PriceBand {

        private final PriceBandCheck check = new PriceBandCheck();

        @ParameterizedTest(name = "price {0} is within the 10% band around 172.45")
        @CsvSource({"172.4500", "189.6900", "155.2100", "160.0000", "185.0000"})
        void insideTheBandPasses(String price) {
            long ticks = com.oms.common.money.Ticks.fromDecimal(new BigDecimal(price));
            assertThatCode(() -> check.check(context(
                    TestFixtures.limitOrder(Side.BUY, 100, price), ticks)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the classic fat finger - an extra zero - is rejected")
        void extraZeroIsRejected() {
            long ticks = com.oms.common.money.Ticks.fromDecimal(new BigDecimal("1724.5000"));
            assertThatThrownBy(() -> check.check(context(
                    TestFixtures.limitOrder(Side.BUY, 100, "1724.5000"), ticks)))
                    .isInstanceOf(RiskRejectedException.class)
                    .hasMessageContaining("outside the permitted band");
        }

        @Test
        @DisplayName("a missing decimal point the other way is rejected too")
        void tenthOfThePriceIsRejected() {
            long ticks = com.oms.common.money.Ticks.fromDecimal(new BigDecimal("17.2450"));
            assertThatThrownBy(() -> check.check(context(
                    TestFixtures.limitOrder(Side.SELL, 100, "17.2450"), ticks)))
                    .isInstanceOf(RiskRejectedException.class);
        }

        @Test
        @DisplayName("no reference price means the band cannot be evaluated, so the order is refused")
        void missingReferencePriceFailsClosed() {
            var instrument = TestFixtures.instrument("0.0001", new BigDecimal("10.00"), 1L, "0.0100");
            var noReference = new com.oms.common.reference.InstrumentView(
                    instrument.symbol(), instrument.name(), instrument.isin(), instrument.currency(),
                    instrument.lotSize(), instrument.tickSizeTicks(), instrument.priceBandPercent(),
                    0L, InstrumentStatus.ACTIVE);

            var ctx = new RiskContext(TestFixtures.limitOrder(Side.BUY, 100, "172.4500"),
                    noReference, TestFixtures.account(), TestFixtures.exposure(), 1_724_500L);

            assertThatThrownBy(() -> check.check(ctx))
                    .isInstanceOf(RiskRejectedException.class)
                    .hasMessageContaining("No reference price");
        }
    }

    @Nested
    @DisplayName("Max order value")
    class MaxOrderValue {

        private final MaxOrderValueCheck check = new MaxOrderValueCheck();

        @Test
        void notionalUnderTheLimitPasses() {
            // 20,000 x 172.45 = 3,449,000 < 5,000,000
            assertThatCode(() -> check.check(context(
                    TestFixtures.limitOrder(Side.BUY, 20_000, "172.4500"), 1_724_500L)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("notional over the limit is rejected")
        void notionalOverTheLimitIsRejected() {
            // 40,000 x 172.45 = 6,898,000 > 5,000,000
            assertThatThrownBy(() -> check.check(context(
                    TestFixtures.limitOrder(Side.BUY, 40_000, "172.4500"), 1_724_500L)))
                    .isInstanceOf(RiskRejectedException.class)
                    .hasMessageContaining("exceeds the account limit");
        }

        @Test
        @DisplayName("a notional that overflows a long is rejected, not wrapped to a negative")
        void overflowIsRejectedNotWrapped() {
            // The bug this guards: quantity x price wrapping to a negative number, which
            // then passes every "notional < limit" comparison ever written.
            var order = TestFixtures.limitOrder(Side.BUY, 90_000_000_000_000L, "172.4500");

            assertThatThrownBy(() -> check.check(context(order, 1_724_500L)))
                    .isInstanceOf(RiskRejectedException.class)
                    .hasMessageContaining("overflow");
        }
    }

    @Nested
    @DisplayName("Max position")
    class MaxPosition {

        private final MaxPositionCheck check = new MaxPositionCheck();

        @Test
        void firstOrderWithinTheLimitPasses() {
            assertThatCode(() -> check.check(context(
                    TestFixtures.limitOrder(Side.BUY, 50_000, "172.4500"), 1_724_500L)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("working orders count towards the limit, not just filled quantity")
        void workingQuantityCounts() {
            // This is the check that a naive implementation gets wrong: 60k already
            // working plus 50k more is 110k against a 100k limit, even though the filled
            // position is still zero.
            PositionExposureEntity exposure = TestFixtures.exposure();
            exposure.addWorking(Side.BUY, 60_000);

            var ctx = new RiskContext(TestFixtures.limitOrder(Side.BUY, 50_000, "172.4500"),
                    TestFixtures.instrument(), TestFixtures.account(), exposure, 1_724_500L);

            assertThatThrownBy(() -> check.check(ctx))
                    .isInstanceOf(RiskRejectedException.class)
                    .hasMessageContaining("Worst-case position");
        }

        @Test
        @DisplayName("a short position is limited by absolute size")
        void shortSideIsLimitedToo() {
            PositionExposureEntity exposure = TestFixtures.exposure();
            exposure.applyFill(Side.SELL, 80_000);   // net -80,000

            var ctx = new RiskContext(TestFixtures.limitOrder(Side.SELL, 30_000, "172.4500"),
                    TestFixtures.instrument(), TestFixtures.account(), exposure, 1_724_500L);

            assertThatThrownBy(() -> check.check(ctx))
                    .isInstanceOf(RiskRejectedException.class);
        }

        @Test
        @DisplayName("selling out of a long position reduces exposure and is allowed")
        void reducingExposureIsAllowed() {
            PositionExposureEntity exposure = TestFixtures.exposure();
            exposure.applyFill(Side.BUY, 90_000);    // net +90,000, close to the limit

            var ctx = new RiskContext(TestFixtures.limitOrder(Side.SELL, 50_000, "172.4500"),
                    TestFixtures.instrument(), TestFixtures.account(), exposure, 1_724_500L);

            // Worst case is +90,000 - 50,000 = +40,000: strictly less exposed.
            assertThatCode(() -> check.check(ctx)).doesNotThrowAnyException();
            assertThat(exposure.worstCaseAfter(Side.SELL, 50_000)).isEqualTo(40_000L);
        }
    }
}
