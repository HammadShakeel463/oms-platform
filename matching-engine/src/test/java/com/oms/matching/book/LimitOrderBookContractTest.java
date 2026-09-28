package com.oms.matching.book;

import com.oms.common.domain.OrderType;
import com.oms.common.domain.Side;
import com.oms.common.domain.TimeInForce;
import com.oms.common.event.CancelReason;
import com.oms.common.money.Ticks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The behaviour every order book must have, run against every implementation.
 *
 * <p>A <b>contract test</b>: this class has no constructor of its own, and the two subclasses
 * supply the implementation. That matters more than it looks. docs/performance.md claims the
 * tuned book is several times faster than the baseline - a claim worth nothing unless both
 * books are proven to do the same thing. Every one of these tests runs twice, and the
 * comparison in the benchmark is meaningful because of it.
 *
 * <p>It is also the honest way to refactor a hot data structure: write the contract against
 * the version you trust, then make the fast one pass it. The alternative - tests written
 * against the implementation you are optimising - tends to encode the bug you are about to
 * introduce.
 */
abstract class LimitOrderBookContractTest {

    protected static final String SYMBOL = "HBL";
    protected static final String ACC_A = "ACC-A";
    protected static final String ACC_B = "ACC-B";

    protected LimitOrderBook book;
    protected RecordingListener listener;

    /** Supplied by each subclass. */
    protected abstract LimitOrderBook newBook(String symbol);

    @BeforeEach
    void setUp() {
        book = newBook(SYMBOL);
        listener = new RecordingListener();
    }

    // --- helpers ---------------------------------------------------------------------

    protected static long ticks(String price) {
        return Ticks.fromDecimal(new BigDecimal(price));
    }

    protected NewOrder limit(Side side, long quantity, String price, String account) {
        return new NewOrder(UUID.randomUUID(), account, side, OrderType.LIMIT,
                TimeInForce.DAY, ticks(price), quantity);
    }

    protected NewOrder limit(Side side, long quantity, String price) {
        return limit(side, quantity, price, ACC_A);
    }

    protected NewOrder market(Side side, long quantity, String account) {
        return new NewOrder(UUID.randomUUID(), account, side, OrderType.MARKET,
                TimeInForce.IOC, Ticks.NO_PRICE, quantity);
    }

    protected NewOrder tif(Side side, long quantity, String price, TimeInForce timeInForce) {
        return new NewOrder(UUID.randomUUID(), ACC_B, side, OrderType.LIMIT,
                timeInForce, ticks(price), quantity);
    }

    // =================================================================================
    //  Resting and crossing
    // =================================================================================

    @Test
    @DisplayName("a non-crossing limit order rests on the book and trades nothing")
    void restingOrder() {
        book.submit(limit(Side.BUY, 1_000, "172.0000"), listener);

        assertThat(listener.trades()).isEmpty();
        assertThat(listener.cancels()).isEmpty();
        assertThat(book.bestBidTicks()).isEqualTo(ticks("172.0000"));
        assertThat(book.bestAskTicks()).isEqualTo(Ticks.NO_PRICE);
        assertThat(book.liveOrderCount()).isEqualTo(1);
        assertThat(book.liveQuantity()).isEqualTo(1_000);
    }

    @Test
    @DisplayName("an aggressive order trades at the RESTING price, not its own limit")
    void priceImprovementAccruesToTheAggressor() {
        book.submit(limit(Side.SELL, 500, "172.0000", ACC_A), listener);
        // A buyer willing to pay 173 meets an offer at 172. The trade prints at 172: the
        // resting order set the price, and the aggressor keeps the improvement.
        book.submit(limit(Side.BUY, 500, "173.0000", ACC_B), listener);

        assertThat(listener.trades()).hasSize(1);
        assertThat(listener.lastTrade().priceTicks()).isEqualTo(ticks("172.0000"));
        assertThat(listener.lastTrade().quantity()).isEqualTo(500);
        assertThat(listener.lastTrade().aggressorSide()).isEqualTo(Side.BUY);
        assertThat(book.liveOrderCount()).isZero();
    }

    @Test
    @DisplayName("both sides of a trade are reported with the right account on the right side")
    void tradeCarriesBothSides() {
        NewOrder resting = limit(Side.SELL, 500, "172.0000", ACC_A);
        NewOrder aggressor = limit(Side.BUY, 500, "173.0000", ACC_B);
        book.submit(resting, listener);
        book.submit(aggressor, listener);

        RecordingListener.Trade trade = listener.lastTrade();
        assertThat(trade.buyOrderId()).isEqualTo(aggressor.orderId());
        assertThat(trade.buyAccountId()).isEqualTo(ACC_B);
        assertThat(trade.sellOrderId()).isEqualTo(resting.orderId());
        assertThat(trade.sellAccountId()).isEqualTo(ACC_A);
    }

    @Test
    @DisplayName("the book is never left crossed")
    void bookIsNeverCrossed() {
        book.submit(limit(Side.BUY, 1_000, "172.5000"), listener);
        book.submit(limit(Side.SELL, 1_000, "172.0000"), listener);

        assertThat(listener.trades()).hasSize(1);
        assertThat(book.snapshot(10).isCrossed()).isFalse();
    }

    // =================================================================================
    //  Priority
    // =================================================================================

    @Test
    @DisplayName("price priority: the better price fills first")
    void pricePriority() {
        book.submit(limit(Side.SELL, 100, "172.5000"), listener);
        book.submit(limit(Side.SELL, 100, "172.0000"), listener);
        book.submit(limit(Side.SELL, 100, "173.0000"), listener);

        book.submit(limit(Side.BUY, 300, "173.0000", ACC_B), listener);

        assertThat(listener.trades())
                .extracting(RecordingListener.Trade::priceTicks)
                .containsExactly(ticks("172.0000"), ticks("172.5000"), ticks("173.0000"));
    }

    @Test
    @DisplayName("time priority: at one price, the earlier order fills first")
    void timePriority() {
        NewOrder first = limit(Side.SELL, 100, "172.0000", "ACC-FIRST");
        NewOrder second = limit(Side.SELL, 100, "172.0000", "ACC-SECOND");
        NewOrder third = limit(Side.SELL, 100, "172.0000", "ACC-THIRD");
        book.submit(first, listener);
        book.submit(second, listener);
        book.submit(third, listener);

        book.submit(limit(Side.BUY, 250, "172.0000", ACC_B), listener);

        assertThat(listener.trades())
                .extracting(RecordingListener.Trade::sellOrderId)
                .containsExactly(first.orderId(), second.orderId(), third.orderId());
        // The third order was only half filled, so it is still at the front of the queue.
        assertThat(listener.trades().get(2).quantity()).isEqualTo(50);
        assertThat(book.liveQuantity()).isEqualTo(50);
    }

    @Test
    @DisplayName("a re-priced order loses its place in the queue - as it must")
    void betterPriceBeatsEarlierArrival() {
        NewOrder early = limit(Side.SELL, 100, "172.5000", "ACC-EARLY");
        NewOrder lateButBetter = limit(Side.SELL, 100, "172.0000", "ACC-LATE");
        book.submit(early, listener);
        book.submit(lateButBetter, listener);

        book.submit(limit(Side.BUY, 100, "173.0000", ACC_B), listener);

        assertThat(listener.lastTrade().sellOrderId()).isEqualTo(lateButBetter.orderId());
    }

    // =================================================================================
    //  Partial fills
    // =================================================================================

    @Test
    @DisplayName("an aggressor larger than the book fills what it can and rests the rest")
    void aggressorRestsResidual() {
        book.submit(limit(Side.SELL, 300, "172.0000"), listener);
        book.submit(limit(Side.BUY, 1_000, "172.0000", ACC_B), listener);

        assertThat(listener.totalTradedQuantity()).isEqualTo(300);
        assertThat(listener.cancels()).isEmpty();
        // 700 is now the best bid.
        assertThat(book.bestBidTicks()).isEqualTo(ticks("172.0000"));
        assertThat(book.liveQuantity()).isEqualTo(700);
    }

    @Test
    @DisplayName("a resting order partially filled keeps its remaining quantity and its place")
    void restingOrderPartiallyFilled() {
        NewOrder resting = limit(Side.SELL, 1_000, "172.0000", ACC_A);
        book.submit(resting, listener);
        book.submit(limit(Side.BUY, 400, "172.0000", ACC_B), listener);

        assertThat(listener.totalTradedQuantity()).isEqualTo(400);
        assertThat(book.liveQuantity()).isEqualTo(600);
        assertThat(book.liveOrderCount()).isEqualTo(1);
        assertThat(book.bestAskTicks()).isEqualTo(ticks("172.0000"));
    }

    @Test
    @DisplayName("a sweep across several levels reports one trade per resting order")
    void sweepAcrossLevels() {
        book.submit(limit(Side.SELL, 100, "172.0000"), listener);
        book.submit(limit(Side.SELL, 100, "172.0000"), listener);
        book.submit(limit(Side.SELL, 100, "172.5000"), listener);
        book.submit(limit(Side.SELL, 100, "173.0000"), listener);

        book.submit(limit(Side.BUY, 350, "173.0000", ACC_B), listener);

        assertThat(listener.trades()).hasSize(4);
        assertThat(listener.totalTradedQuantity()).isEqualTo(350);
        assertThat(book.liveQuantity()).isEqualTo(50);
    }

    // =================================================================================
    //  Market orders
    // =================================================================================

    @Test
    @DisplayName("a market order sweeps at any price and never rests")
    void marketOrderSweeps() {
        book.submit(limit(Side.SELL, 100, "172.0000"), listener);
        book.submit(limit(Side.SELL, 100, "180.0000"), listener);
        book.submit(limit(Side.SELL, 100, "250.0000"), listener);

        book.submit(market(Side.BUY, 250, ACC_B), listener);

        assertThat(listener.totalTradedQuantity()).isEqualTo(250);
        assertThat(listener.trades())
                .extracting(RecordingListener.Trade::priceTicks)
                .containsExactly(ticks("172.0000"), ticks("180.0000"), ticks("250.0000"));
        assertThat(book.liveQuantity()).isEqualTo(50);
    }

    @Test
    @DisplayName("a market order with nothing to trade against is cancelled, not rested")
    void marketOrderOnEmptyBook() {
        book.submit(market(Side.BUY, 500, ACC_B), listener);

        assertThat(listener.trades()).isEmpty();
        assertThat(listener.lastCancel().reason()).isEqualTo(CancelReason.IOC_RESIDUAL);
        assertThat(listener.lastCancel().quantity()).isEqualTo(500);
        assertThat(book.liveOrderCount())
                .as("a market order has no price, so there is no price at which to rest it")
                .isZero();
    }

    @Test
    @DisplayName("a market order residual is cancelled after a partial sweep")
    void marketOrderResidualCancelled() {
        book.submit(limit(Side.SELL, 100, "172.0000"), listener);
        book.submit(market(Side.BUY, 500, ACC_B), listener);

        assertThat(listener.totalTradedQuantity()).isEqualTo(100);
        assertThat(listener.lastCancel().reason()).isEqualTo(CancelReason.IOC_RESIDUAL);
        assertThat(listener.lastCancel().quantity()).isEqualTo(400);
    }

    // =================================================================================
    //  Time in force
    // =================================================================================

    @Test
    @DisplayName("IOC fills what it can and cancels the residual")
    void iocCancelsResidual() {
        book.submit(limit(Side.SELL, 100, "172.0000"), listener);
        book.submit(tif(Side.BUY, 500, "172.0000", TimeInForce.IOC), listener);

        assertThat(listener.totalTradedQuantity()).isEqualTo(100);
        assertThat(listener.lastCancel().reason()).isEqualTo(CancelReason.IOC_RESIDUAL);
        assertThat(listener.lastCancel().quantity()).isEqualTo(400);
        assertThat(book.liveOrderCount()).isZero();
    }

    @Test
    @DisplayName("FOK that cannot be filled in full trades nothing at all")
    void fokAllOrNothing() {
        book.submit(limit(Side.SELL, 100, "172.0000"), listener);
        book.submit(tif(Side.BUY, 500, "172.0000", TimeInForce.FOK), listener);

        assertThat(listener.trades())
                .as("FOK must be decided before anything is filled - there is no rollback "
                        + "for a fill that has already been published")
                .isEmpty();
        assertThat(listener.lastCancel().reason()).isEqualTo(CancelReason.FOK_UNFILLABLE);
        assertThat(listener.lastCancel().quantity()).isEqualTo(500);
        // The resting order is untouched.
        assertThat(book.liveQuantity()).isEqualTo(100);
    }

    @Test
    @DisplayName("FOK that can be filled in full is filled in full")
    void fokFillable() {
        book.submit(limit(Side.SELL, 300, "172.0000"), listener);
        book.submit(limit(Side.SELL, 300, "172.5000"), listener);

        book.submit(tif(Side.BUY, 500, "172.5000", TimeInForce.FOK), listener);

        assertThat(listener.totalTradedQuantity()).isEqualTo(500);
        assertThat(listener.cancels()).isEmpty();
        assertThat(book.liveQuantity()).isEqualTo(100);
    }

    @Test
    @DisplayName("FOK feasibility ignores levels outside the limit price")
    void fokRespectsTheLimit() {
        book.submit(limit(Side.SELL, 100, "172.0000"), listener);
        book.submit(limit(Side.SELL, 900, "200.0000"), listener);

        // 1,000 available in total, but only 100 at or below 172.50.
        book.submit(tif(Side.BUY, 1_000, "172.5000", TimeInForce.FOK), listener);

        assertThat(listener.trades()).isEmpty();
        assertThat(listener.lastCancel().reason()).isEqualTo(CancelReason.FOK_UNFILLABLE);
    }

    // =================================================================================
    //  Cancellation
    // =================================================================================

    @Test
    @DisplayName("cancelling a resting order removes it and reports the remaining quantity")
    void cancelRestingOrder() {
        NewOrder order = limit(Side.BUY, 1_000, "172.0000", ACC_A);
        book.submit(order, listener);

        boolean cancelled = book.cancel(order.orderId(), ACC_A, listener);

        assertThat(cancelled).isTrue();
        assertThat(listener.lastCancel().reason()).isEqualTo(CancelReason.USER_REQUEST);
        assertThat(listener.lastCancel().quantity()).isEqualTo(1_000);
        assertThat(book.liveOrderCount()).isZero();
        assertThat(book.liveQuantity()).isZero();
        assertThat(book.bestBidTicks()).isEqualTo(Ticks.NO_PRICE);
    }

    @Test
    @DisplayName("cancelling after a partial fill reports only what is left")
    void cancelAfterPartialFill() {
        NewOrder order = limit(Side.SELL, 1_000, "172.0000", ACC_A);
        book.submit(order, listener);
        book.submit(limit(Side.BUY, 400, "172.0000", ACC_B), listener);

        book.cancel(order.orderId(), ACC_A, listener);

        assertThat(listener.lastCancel().quantity()).isEqualTo(600);
        assertThat(book.liveQuantity()).isZero();
    }

    @Test
    @DisplayName("cancelling an order the book does not have reports UNKNOWN_ORDER")
    void cancelUnknownOrder() {
        boolean cancelled = book.cancel(UUID.randomUUID(), ACC_A, listener);

        assertThat(cancelled).isFalse();
        assertThat(listener.lastCancel().reason())
                .as("the engine owns the book, so 'I do not have it' is an answer, not an error")
                .isEqualTo(CancelReason.UNKNOWN_ORDER);
        assertThat(listener.lastCancel().quantity()).isZero();
    }

    @Test
    @DisplayName("cancelling a fully filled order reports UNKNOWN_ORDER")
    void cancelFilledOrder() {
        NewOrder order = limit(Side.SELL, 100, "172.0000", ACC_A);
        book.submit(order, listener);
        book.submit(limit(Side.BUY, 100, "172.0000", ACC_B), listener);

        boolean cancelled = book.cancel(order.orderId(), ACC_A, listener);

        assertThat(cancelled).isFalse();
        assertThat(listener.lastCancel().reason()).isEqualTo(CancelReason.UNKNOWN_ORDER);
    }

    @Test
    @DisplayName("cancelling twice is safe: the second is UNKNOWN_ORDER")
    void doubleCancelIsSafe() {
        NewOrder order = limit(Side.BUY, 100, "172.0000", ACC_A);
        book.submit(order, listener);

        assertThat(book.cancel(order.orderId(), ACC_A, listener)).isTrue();
        assertThat(book.cancel(order.orderId(), ACC_A, listener)).isFalse();
        assertThat(listener.cancels()).hasSize(2);
    }

    @Test
    @DisplayName("cancelling the middle of a queue leaves the others in order")
    void cancelFromMiddleOfQueue() {
        NewOrder first = limit(Side.SELL, 100, "172.0000", "ACC-1");
        NewOrder middle = limit(Side.SELL, 100, "172.0000", "ACC-2");
        NewOrder last = limit(Side.SELL, 100, "172.0000", "ACC-3");
        book.submit(first, listener);
        book.submit(middle, listener);
        book.submit(last, listener);

        book.cancel(middle.orderId(), "ACC-2", listener);
        listener.clear();

        book.submit(limit(Side.BUY, 200, "172.0000", ACC_B), listener);

        assertThat(listener.trades())
                .extracting(RecordingListener.Trade::sellOrderId)
                .containsExactly(first.orderId(), last.orderId());
    }

    // =================================================================================
    //  Sequencing
    // =================================================================================

    @Test
    @DisplayName("sequence numbers are strictly increasing across trades and cancels")
    void sequencesAreStrictlyIncreasing() {
        book.submit(limit(Side.SELL, 100, "172.0000"), listener);
        book.submit(limit(Side.SELL, 100, "172.5000"), listener);
        book.submit(market(Side.BUY, 500, ACC_B), listener);
        book.cancel(UUID.randomUUID(), ACC_A, listener);

        var sequences = listener.sequences();
        assertThat(sequences).isNotEmpty();
        for (int i = 1; i < sequences.size(); i++) {
            assertThat(sequences.get(i)).isGreaterThan(sequences.get(i - 1));
        }
        assertThat(book.sequence()).isEqualTo(sequences.get(sequences.size() - 1));
    }

    // =================================================================================
    //  Depth
    // =================================================================================

    @Test
    @DisplayName("a snapshot aggregates quantity and order count per level, best price first")
    void snapshotAggregatesLevels() {
        book.submit(limit(Side.BUY, 100, "172.0000"), listener);
        book.submit(limit(Side.BUY, 200, "172.0000"), listener);
        book.submit(limit(Side.BUY, 300, "171.5000"), listener);
        book.submit(limit(Side.SELL, 150, "173.0000"), listener);

        BookSnapshot snapshot = book.snapshot(10);

        assertThat(snapshot.bids()).hasSize(2);
        assertThat(snapshot.bids().get(0).priceTicks()).isEqualTo(ticks("172.0000"));
        assertThat(snapshot.bids().get(0).quantity()).isEqualTo(300);
        assertThat(snapshot.bids().get(0).orderCount()).isEqualTo(2);
        assertThat(snapshot.bids().get(1).priceTicks()).isEqualTo(ticks("171.5000"));
        assertThat(snapshot.asks()).hasSize(1);
        assertThat(snapshot.spreadTicks()).isEqualTo(ticks("1.0000"));
        assertThat(snapshot.isCrossed()).isFalse();
    }

    @Test
    @DisplayName("a snapshot is limited to the requested depth")
    void snapshotRespectsDepth() {
        for (int i = 0; i < 20; i++) {
            book.submit(limit(Side.BUY, 100, "17" + (i % 10) + ".0000"), listener);
        }

        assertThat(book.snapshot(3).bids()).hasSize(3);
    }

    @Test
    @DisplayName("an empty book reports no prices and is not crossed")
    void emptyBook() {
        BookSnapshot snapshot = book.snapshot(10);

        assertThat(snapshot.bestBidTicks()).isEqualTo(Ticks.NO_PRICE);
        assertThat(snapshot.bestAskTicks()).isEqualTo(Ticks.NO_PRICE);
        assertThat(snapshot.spreadTicks()).isEqualTo(Ticks.NO_PRICE);
        assertThat(snapshot.midPriceTicks()).isEqualTo(Ticks.NO_PRICE);
        assertThat(snapshot.isCrossed()).isFalse();
        assertThat(book.liveQuantity()).isZero();
    }
}
