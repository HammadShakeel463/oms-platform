package com.oms.common.domain;

import com.oms.common.money.Ticks;
import com.oms.common.reference.InstrumentStatus;
import com.oms.common.reference.InstrumentView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The small domain predicates the whole platform branches on.
 *
 * <p>These are one-liners, which is exactly why they are worth testing: {@code crosses} is the
 * definition of "this order can trade against that price", and it is asymmetric between buy and
 * sell. Getting it backwards for one side produces a matching engine that is confidently wrong
 * in half of all cases, and no type system catches it.
 */
class TradingVocabularyTest {

    @Nested
    @DisplayName("Side")
    class Sides {

        @Test
        @DisplayName("the sign is what turns a quantity into a signed position delta")
        void signsArePositionDeltas() {
            assertThat(Side.BUY.sign()).isEqualTo(1);
            assertThat(Side.SELL.sign()).isEqualTo(-1);
        }

        @Test
        @DisplayName("opposite() is an involution - the contra side of the contra side is this side")
        void oppositeIsAnInvolution() {
            assertThat(Side.BUY.opposite()).isEqualTo(Side.SELL);
            assertThat(Side.SELL.opposite()).isEqualTo(Side.BUY);

            for (Side side : Side.values()) {
                assertThat(side.opposite().opposite()).isEqualTo(side);
            }
        }

        @ParameterizedTest(name = "{0} limit {2} crosses a price of {1}? {3}")
        @CsvSource({
                // A buy crosses when the market is at or below its limit.
                "BUY,  1724000, 1725000, true",
                "BUY,  1725000, 1725000, true",   // at the limit is a cross: price-time, not strict
                "BUY,  1726000, 1725000, false",
                // A sell crosses when the market is at or above its limit. The mirror image.
                "SELL, 1726000, 1725000, true",
                "SELL, 1725000, 1725000, true",
                "SELL, 1724000, 1725000, false",
        })
        @DisplayName("crosses() is the asymmetric heart of matching, and equality counts as a cross")
        void crossesIsAsymmetric(Side side, long priceTicks, long limitTicks, boolean expected) {
            assertThat(side.crosses(priceTicks, limitTicks)).isEqualTo(expected);
        }

        @Test
        @DisplayName("the 'infinitely aggressive' limit is a different extreme for each side")
        void marketOrdersCrossEverything() {
            // MARKET is modelled as a limit that crosses anything, and because crosses() is
            // asymmetric that limit is Long.MIN_VALUE for a sell and Long.MAX_VALUE for a buy.
            // Ticks.NO_PRICE is MIN_VALUE, so it is the sell-side extreme; the engine widens a
            // buy to the other end rather than reusing the same sentinel for both.
            assertThat(Ticks.NO_PRICE).isEqualTo(Long.MIN_VALUE);

            assertThat(Side.SELL.crosses(1L, Long.MIN_VALUE)).isTrue();
            assertThat(Side.SELL.crosses(Long.MAX_VALUE, Long.MIN_VALUE)).isTrue();
            assertThat(Side.BUY.crosses(1L, Long.MAX_VALUE)).isTrue();
            assertThat(Side.BUY.crosses(Long.MAX_VALUE, Long.MAX_VALUE)).isTrue();

            assertThat(Side.BUY.crosses(1L, Long.MIN_VALUE))
                    .as("using the sell sentinel for a buy would silently cross nothing")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("OrderType and TimeInForce")
    class TypesAndDurations {

        @Test
        @DisplayName("only LIMIT requires a price - the validation rule reads off this method")
        void onlyLimitRequiresAPrice() {
            assertThat(OrderType.LIMIT.requiresLimitPrice()).isTrue();
            assertThat(OrderType.MARKET.requiresLimitPrice()).isFalse();
        }

        @Test
        @DisplayName("IOC and FOK are immediate; DAY rests on the book")
        void immediacyIsWhatDecidesWhetherAnOrderRests() {
            assertThat(TimeInForce.IOC.isImmediate()).isTrue();
            assertThat(TimeInForce.FOK.isImmediate()).isTrue();
            assertThat(TimeInForce.DAY.isImmediate())
                    .as("a DAY order that did not rest would be a market order with extra steps")
                    .isFalse();
        }

        @ParameterizedTest
        @EnumSource(TimeInForce.class)
        @DisplayName("every time-in-force answers the immediacy question without a default branch")
        void everyTimeInForceIsClassified(TimeInForce tif) {
            assertThat(tif.isImmediate()).isIn(true, false);
        }
    }

    @Nested
    @DisplayName("InstrumentView")
    class Instruments {

        @Test
        @DisplayName("only an ACTIVE instrument is tradeable - a halt is not a soft warning")
        void onlyActiveIsTradeable() {
            assertThat(instrument(InstrumentStatus.ACTIVE).isTradeable()).isTrue();
            assertThat(instrument(InstrumentStatus.HALTED).isTradeable()).isFalse();
            assertThat(instrument(InstrumentStatus.DELISTED).isTradeable()).isFalse();
        }

        @Test
        @DisplayName("the view carries everything the risk checks need, so no second lookup is required")
        void carriesWhatRiskNeeds() {
            InstrumentView hbl = instrument(InstrumentStatus.ACTIVE);

            assertThat(hbl.symbol()).isEqualTo("HBL");
            assertThat(hbl.lotSize()).isEqualTo(500);
            assertThat(hbl.tickSizeTicks()).isEqualTo(100);
            assertThat(hbl.priceBandPercent()).isEqualByComparingTo("5.00");
            assertThat(hbl.referencePriceTicks()).isEqualTo(1_724_500L);
        }

        private InstrumentView instrument(InstrumentStatus status) {
            return new InstrumentView("HBL", "Habib Bank Limited", "PK0001601008", "PKR",
                    500, 100, new BigDecimal("5.00"), 1_724_500L, status);
        }
    }
}
