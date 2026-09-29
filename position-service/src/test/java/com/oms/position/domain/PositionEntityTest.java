package com.oms.position.domain;

import com.oms.common.domain.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Average-cost P&amp;L arithmetic.
 *
 * <p>This is the most consequential test class in the platform. Every other bug produces a wrong
 * status or a slow response; a bug here produces a wrong <em>number</em>, silently, and nobody finds
 * out until somebody reconciles by hand. The cases are written to mirror the way a trader would
 * check them: known fills, hand-computed answers.
 */
class PositionEntityTest {

    private static final String ACCOUNT = "ACC-TRADER-1";
    private static final String SYMBOL = "HBL";

    private static PositionEntity flat() {
        return new PositionEntity(ACCOUNT, SYMBOL);
    }

    private static BigDecimal price(String value) {
        return new BigDecimal(value);
    }

    @Nested
    @DisplayName("Opening and increasing")
    class Opening {

        @Test
        @DisplayName("a first buy opens a long at that price")
        void firstBuy() {
            PositionEntity position = flat();

            BigDecimal realised = position.applyFill(Side.BUY, price("172.0000"), 100);

            assertThat(position.getNetQuantity()).isEqualTo(100);
            assertThat(position.averageCost()).isEqualByComparingTo("172.0000");
            assertThat(realised).isEqualByComparingTo("0");
            assertThat(position.getRealisedPnl()).isEqualByComparingTo("0");
            assertThat(position.isLong()).isTrue();
        }

        @Test
        @DisplayName("a first sell opens a short at that price")
        void firstSell() {
            PositionEntity position = flat();

            position.applyFill(Side.SELL, price("172.0000"), 100);

            assertThat(position.getNetQuantity()).isEqualTo(-100);
            assertThat(position.averageCost()).isEqualByComparingTo("172.0000");
            assertThat(position.isShort()).isTrue();
        }

        @Test
        @DisplayName("increasing a long blends the cost, weighted by quantity")
        void increasingBlendsCost() {
            PositionEntity position = flat();

            position.applyFill(Side.BUY, price("172.0000"), 100);
            BigDecimal realised = position.applyFill(Side.BUY, price("174.0000"), 300);

            // (100 x 172 + 300 x 174) / 400 = 173.50
            assertThat(position.getNetQuantity()).isEqualTo(400);
            assertThat(position.averageCost()).isEqualByComparingTo("173.5000");
            assertThat(realised)
                    .as("increasing a position realises nothing")
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("increasing a short blends the cost the same way")
        void increasingShortBlendsCost() {
            PositionEntity position = flat();

            position.applyFill(Side.SELL, price("100.0000"), 100);
            position.applyFill(Side.SELL, price("110.0000"), 100);

            assertThat(position.getNetQuantity()).isEqualTo(-200);
            assertThat(position.averageCost()).isEqualByComparingTo("105.0000");
        }

        @Test
        @DisplayName("repeated increases do not accumulate rounding error")
        void noRoundingDrift() {
            // The bug this guards: recomputing an average from a previously ROUNDED average, over
            // and over. Storing exact open cost instead means the average is derived fresh every
            // time, so 1,000 fills at a price that does not divide evenly still land exactly.
            PositionEntity position = flat();
            for (int i = 0; i < 1_000; i++) {
                position.applyFill(Side.BUY, price("33.3333"), 3);
            }

            assertThat(position.getNetQuantity()).isEqualTo(3_000);
            assertThat(position.averageCost()).isEqualByComparingTo("33.3333");
            assertThat(position.getOpenCost()).isEqualByComparingTo("99999.9000");
        }
    }

    @Nested
    @DisplayName("Reducing and closing")
    class Reducing {

        @Test
        @DisplayName("selling part of a long realises profit on the part sold only")
        void partialCloseAtProfit() {
            PositionEntity position = flat();
            position.applyFill(Side.BUY, price("100.0000"), 1_000);

            BigDecimal realised = position.applyFill(Side.SELL, price("110.0000"), 400);

            // (110 - 100) x 400 = 4,000
            assertThat(realised).isEqualByComparingTo("4000.0000");
            assertThat(position.getNetQuantity()).isEqualTo(600);
            assertThat(position.averageCost())
                    .as("the remaining position keeps its original cost basis")
                    .isEqualByComparingTo("100.0000");
        }

        @Test
        @DisplayName("selling at a loss realises a negative figure, which is not an error")
        void partialCloseAtLoss() {
            PositionEntity position = flat();
            position.applyFill(Side.BUY, price("100.0000"), 1_000);

            BigDecimal realised = position.applyFill(Side.SELL, price("92.5000"), 200);

            assertThat(realised).isEqualByComparingTo("-1500.0000");
            assertThat(position.getRealisedPnl()).isEqualByComparingTo("-1500.0000");
        }

        @Test
        @DisplayName("buying back part of a short realises profit when the price fell")
        void shortCoveredAtProfit() {
            PositionEntity position = flat();
            position.applyFill(Side.SELL, price("100.0000"), 500);

            BigDecimal realised = position.applyFill(Side.BUY, price("90.0000"), 200);

            // Sold at 100, bought back at 90: (100 - 90) x 200 = 2,000
            assertThat(realised).isEqualByComparingTo("2000.0000");
            assertThat(position.getNetQuantity()).isEqualTo(-300);
        }

        @Test
        @DisplayName("a short that moves against you realises a loss")
        void shortCoveredAtLoss() {
            PositionEntity position = flat();
            position.applyFill(Side.SELL, price("100.0000"), 500);

            BigDecimal realised = position.applyFill(Side.BUY, price("115.0000"), 500);

            assertThat(realised).isEqualByComparingTo("-7500.0000");
            assertThat(position.isFlat()).isTrue();
        }

        @Test
        @DisplayName("a full close leaves the position flat with EXACTLY zero open cost")
        void fullCloseBalancesExactly() {
            // The property the schema enforces with a CHECK constraint. A price that does not
            // divide evenly is the case where a rounded-average implementation leaves a fraction
            // of a rupee of phantom cost behind.
            PositionEntity position = flat();
            position.applyFill(Side.BUY, price("33.3333"), 7);
            position.applyFill(Side.BUY, price("77.7777"), 11);

            position.applyFill(Side.SELL, price("50.0000"), 18);

            assertThat(position.isFlat()).isTrue();
            assertThat(position.getOpenCost())
                    .as("a flat position must have exactly zero open cost, or the database "
                            + "CHECK constraint rejects the write")
                    .isEqualByComparingTo("0");
            assertThat(position.averageCost()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("round trip at the same price realises nothing")
        void roundTripAtSamePriceIsFlat() {
            PositionEntity position = flat();
            position.applyFill(Side.BUY, price("172.4500"), 1_000);
            position.applyFill(Side.SELL, price("172.4500"), 1_000);

            assertThat(position.isFlat()).isTrue();
            assertThat(position.getRealisedPnl()).isEqualByComparingTo("0");
            assertThat(position.getOpenCost()).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("Crossing through zero - the case implementations get wrong")
    class CrossingZero {

        @Test
        @DisplayName("selling more than a long realises on the closed part and opens a short at the new price")
        void longToShort() {
            PositionEntity position = flat();
            position.applyFill(Side.BUY, price("100.0000"), 100);

            // Sell 150 against a long of 100: close 100, open a short of 50.
            BigDecimal realised = position.applyFill(Side.SELL, price("110.0000"), 150);

            assertThat(realised)
                    .as("realised on the 100 that was actually held, NOT on all 150 - "
                            + "the extra 50 was never owned, so there is no profit on it")
                    .isEqualByComparingTo("1000.0000");
            assertThat(position.getNetQuantity()).isEqualTo(-50);
            assertThat(position.averageCost())
                    .as("the new short is opened at THIS fill's price, not at the old long's "
                            + "average - carrying the old basis across would misprice it for ever")
                    .isEqualByComparingTo("110.0000");
            assertThat(position.getOpenCost()).isEqualByComparingTo("5500.0000");
        }

        @Test
        @DisplayName("buying more than a short flips to long the same way")
        void shortToLong() {
            PositionEntity position = flat();
            position.applyFill(Side.SELL, price("100.0000"), 200);

            BigDecimal realised = position.applyFill(Side.BUY, price("95.0000"), 300);

            // Covered 200 at 95 having sold at 100: (100 - 95) x 200 = 1,000
            assertThat(realised).isEqualByComparingTo("1000.0000");
            assertThat(position.getNetQuantity()).isEqualTo(100);
            assertThat(position.averageCost()).isEqualByComparingTo("95.0000");
            assertThat(position.isLong()).isTrue();
        }

        @Test
        @DisplayName("a flip through zero and back reconciles against the cash flows")
        void flipAndBackReconciles() {
            // The end-to-end check a trader would do: after returning to flat, realised P&L must
            // equal total proceeds minus total cost, with no reference to averages at all.
            PositionEntity position = flat();
            position.applyFill(Side.BUY, price("100.0000"), 100);    // cost      10,000
            position.applyFill(Side.SELL, price("120.0000"), 250);   // proceeds  30,000
            position.applyFill(Side.BUY, price("110.0000"), 150);    // cost      16,500

            BigDecimal cashIn = price("30000.0000");
            BigDecimal cashOut = price("10000.0000").add(price("16500.0000"));

            assertThat(position.isFlat()).isTrue();
            assertThat(position.getRealisedPnl())
                    .as("realised must equal proceeds minus cost once flat")
                    .isEqualByComparingTo(cashIn.subtract(cashOut));
            assertThat(position.getOpenCost()).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("Mark to market")
    class MarkToMarket {

        @Test
        @DisplayName("a long gains when the mark is above cost")
        void longUnrealised() {
            PositionEntity position = flat();
            position.applyFill(Side.BUY, price("100.0000"), 500);

            assertThat(position.unrealisedPnl(price("104.0000"))).isEqualByComparingTo("2000.0000");
            assertThat(position.marketValue(price("104.0000"))).isEqualByComparingTo("52000.0000");
        }

        @Test
        @DisplayName("a short gains when the mark is below cost - the sign works without a branch")
        void shortUnrealised() {
            PositionEntity position = flat();
            position.applyFill(Side.SELL, price("100.0000"), 500);

            // (96 - 100) x (-500) = +2,000
            assertThat(position.unrealisedPnl(price("96.0000"))).isEqualByComparingTo("2000.0000");
            assertThat(position.unrealisedPnl(price("103.0000"))).isEqualByComparingTo("-1500.0000");
            assertThat(position.marketValue(price("96.0000")))
                    .as("a short position has negative market value")
                    .isEqualByComparingTo("-48000.0000");
        }

        @Test
        @DisplayName("no mark means null, never a fabricated zero")
        void unmarkedPositionIsUnknownNotZero() {
            PositionEntity position = flat();
            position.applyFill(Side.BUY, price("100.0000"), 500);

            assertThat(position.unrealisedPnl(null))
                    .as("a risk screen that renders an unmarked position as flat is how somebody "
                            + "concludes they have no exposure")
                    .isNull();
            assertThat(position.marketValue(null)).isNull();
        }

        @Test
        @DisplayName("a flat position has zero unrealised even with no mark")
        void flatPositionIsZeroNotUnknown() {
            assertThat(flat().unrealisedPnl(null)).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("Bookkeeping and guards")
    class Guards {

        @Test
        @DisplayName("bought, sold and fill counts accumulate")
        void counters() {
            PositionEntity position = flat();
            position.applyFill(Side.BUY, price("100.0000"), 100);
            position.applyFill(Side.BUY, price("101.0000"), 50);
            position.applyFill(Side.SELL, price("102.0000"), 30);

            assertThat(position.getBoughtQuantity()).isEqualTo(150);
            assertThat(position.getSoldQuantity()).isEqualTo(30);
            assertThat(position.getFillCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("a non-positive quantity is rejected")
        void quantityMustBePositive() {
            assertThatThrownBy(() -> flat().applyFill(Side.BUY, price("100.0000"), 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("quantity must be positive");
        }

        @Test
        @DisplayName("a non-positive price is rejected")
        void priceMustBePositive() {
            assertThatThrownBy(() -> flat().applyFill(Side.BUY, price("0"), 100))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("price must be positive");
            assertThatThrownBy(() -> flat().applyFill(Side.BUY, null, 100))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
