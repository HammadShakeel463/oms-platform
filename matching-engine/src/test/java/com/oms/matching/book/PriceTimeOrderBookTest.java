package com.oms.matching.book;

import com.oms.common.domain.OrderType;
import com.oms.common.domain.Side;
import com.oms.common.domain.TimeInForce;
import com.oms.common.money.Ticks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests specific to the tuned book: the things that are implementation choices rather than
 * behaviour, and therefore not part of the shared contract.
 *
 * <p>These are the tests that stop an optimisation from silently regressing. A pool that
 * quietly stops recycling, or a price level that is never removed when it empties, changes no
 * observable behaviour and shows up only as a slow leak in production.
 */
class PriceTimeOrderBookTest {

    private static final String SYMBOL = "HBL";
    private static final String ACCOUNT = "ACC-A";

    private PriceTimeOrderBook book;
    private RecordingListener listener;

    @BeforeEach
    void setUp() {
        book = new PriceTimeOrderBook(SYMBOL);
        listener = new RecordingListener();
    }

    private static long ticks(String price) {
        return Ticks.fromDecimal(new BigDecimal(price));
    }

    private NewOrder limit(Side side, long quantity, String price) {
        return new NewOrder(UUID.randomUUID(), ACCOUNT, side, OrderType.LIMIT,
                TimeInForce.DAY, ticks(price), quantity);
    }

    @Test
    @DisplayName("an emptied price level is removed from the tree, not left behind")
    void emptyLevelsAreRemoved() {
        NewOrder order = limit(Side.BUY, 100, "172.0000");
        book.submit(order, listener);
        assertThat(book.bidLevelCount()).isEqualTo(1);

        book.cancel(order.orderId(), ACCOUNT, listener);

        assertThat(book.bidLevelCount())
                .as("a level left in the tree after its last order leaves is a slow leak "
                        + "and makes every subsequent tree walk longer")
                .isZero();
        assertThat(book.bestBidTicks()).isEqualTo(Ticks.NO_PRICE);
    }

    @Test
    @DisplayName("a level emptied by a fill is removed too")
    void levelEmptiedByFillIsRemoved() {
        book.submit(limit(Side.SELL, 100, "172.0000"), listener);
        book.submit(limit(Side.BUY, 100, "172.0000"), listener);

        assertThat(book.askLevelCount()).isZero();
        assertThat(book.bidLevelCount()).isZero();
    }

    @Test
    @DisplayName("nodes are recycled through the pool after a fill")
    void nodesAreRecycledAfterFill() {
        book.submit(limit(Side.SELL, 100, "172.0000"), listener);
        assertThat(book.pooledNodeCount()).isZero();

        book.submit(limit(Side.BUY, 100, "172.0000"), listener);

        assertThat(book.pooledNodeCount())
                .as("the filled order's node should be back in the free list")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("nodes are recycled after a cancel")
    void nodesAreRecycledAfterCancel() {
        NewOrder order = limit(Side.BUY, 100, "172.0000");
        book.submit(order, listener);
        book.cancel(order.orderId(), ACCOUNT, listener);

        assertThat(book.pooledNodeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a steady state of place-and-cancel allocates no new nodes")
    void steadyStateReusesNodes() {
        // The shape of real market-maker traffic: quote, pull, requote. If this allocated a
        // node per cycle it would be the single largest source of garbage in the engine.
        for (int i = 0; i < 500; i++) {
            NewOrder order = limit(Side.BUY, 100, "172.0000");
            book.submit(order, listener);
            book.cancel(order.orderId(), ACCOUNT, listener);
        }

        assertThat(book.pooledNodeCount())
                .as("one node, created once, reused 500 times")
                .isEqualTo(1);
        assertThat(book.liveOrderCount()).isZero();
    }

    @Test
    @DisplayName("the pool is bounded, so a burst cannot make the cache the leak")
    void poolIsBounded() {
        PriceTimeOrderBook bounded = new PriceTimeOrderBook(SYMBOL, 8);

        var orders = new java.util.ArrayList<NewOrder>();
        for (int i = 0; i < 50; i++) {
            NewOrder order = limit(Side.BUY, 100, "17" + (i % 9) + ".0000");
            orders.add(order);
            bounded.submit(order, listener);
        }
        orders.forEach(o -> bounded.cancel(o.orderId(), ACCOUNT, listener));

        assertThat(bounded.pooledNodeCount()).isEqualTo(8);
    }

    @Test
    @DisplayName("pooling can be switched off, which is how the benchmark isolates its effect")
    void poolingCanBeDisabled() {
        PriceTimeOrderBook unpooled = new PriceTimeOrderBook(SYMBOL, 0);

        NewOrder order = limit(Side.BUY, 100, "172.0000");
        unpooled.submit(order, listener);
        unpooled.cancel(order.orderId(), ACCOUNT, listener);

        assertThat(unpooled.pooledNodeCount()).isZero();
    }

    @Test
    @DisplayName("a recycled node carries none of the previous order's state")
    void recycledNodesAreClean() {
        NewOrder first = limit(Side.BUY, 100, "172.0000");
        book.submit(first, listener);
        book.cancel(first.orderId(), ACCOUNT, listener);

        NewOrder second = new NewOrder(UUID.randomUUID(), "ACC-OTHER", Side.SELL,
                OrderType.LIMIT, TimeInForce.DAY, ticks("180.0000"), 250);
        book.submit(second, listener);
        listener.clear();

        // If the reused node had kept the old id, this cancel would fail; if it had kept the
        // old account, the ownership check would reject it.
        assertThat(book.cancel(second.orderId(), "ACC-OTHER", listener)).isTrue();
        assertThat(listener.lastCancel().quantity()).isEqualTo(250);
        assertThat(book.cancel(first.orderId(), ACCOUNT, listener)).isFalse();
    }

    @Test
    @DisplayName("a cancel from the wrong account is refused")
    void cancelFromWrongAccountIsRefused() {
        NewOrder order = limit(Side.BUY, 100, "172.0000");
        book.submit(order, listener);

        boolean cancelled = book.cancel(order.orderId(), "ACC-IMPOSTER", listener);

        assertThat(cancelled).isFalse();
        assertThat(book.liveOrderCount())
                .as("the order must survive a cancel attempt by another account")
                .isEqualTo(1);
        // And the rightful owner can still cancel it - the failed attempt did not evict it
        // from the index.
        assertThat(book.cancel(order.orderId(), ACCOUNT, listener)).isTrue();
    }

    @Test
    @DisplayName("level counts track both sides independently")
    void levelCounts() {
        book.submit(limit(Side.BUY, 100, "172.0000"), listener);
        book.submit(limit(Side.BUY, 100, "171.0000"), listener);
        book.submit(limit(Side.SELL, 100, "173.0000"), listener);

        assertThat(book.bidLevelCount()).isEqualTo(2);
        assertThat(book.askLevelCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("clear empties everything, including the pool")
    void clearResetsTheBook() {
        book.submit(limit(Side.BUY, 100, "172.0000"), listener);
        book.submit(limit(Side.SELL, 100, "173.0000"), listener);

        book.clear();

        assertThat(book.liveOrderCount()).isZero();
        assertThat(book.liveQuantity()).isZero();
        assertThat(book.bidLevelCount()).isZero();
        assertThat(book.askLevelCount()).isZero();
        assertThat(book.pooledNodeCount()).isZero();
    }

    @Test
    @DisplayName("a market order is modelled as an infinitely aggressive limit")
    void marketOrderEffectiveLimit() {
        NewOrder buy = new NewOrder(UUID.randomUUID(), ACCOUNT, Side.BUY, OrderType.MARKET,
                TimeInForce.IOC, Ticks.NO_PRICE, 100);
        NewOrder sell = new NewOrder(UUID.randomUUID(), ACCOUNT, Side.SELL, OrderType.MARKET,
                TimeInForce.IOC, Ticks.NO_PRICE, 100);

        assertThat(buy.effectiveLimitTicks()).isEqualTo(Long.MAX_VALUE);
        assertThat(sell.effectiveLimitTicks())
                .as("MIN_VALUE + 1, because MIN_VALUE is the NO_PRICE sentinel and the two "
                        + "must stay distinguishable")
                .isEqualTo(Long.MIN_VALUE + 1);
        assertThat(sell.effectiveLimitTicks()).isNotEqualTo(Ticks.NO_PRICE);
    }

    @Test
    @DisplayName("a LIMIT order with no price cannot be constructed")
    void limitOrderNeedsAPrice() {
        assertThatThrownBy(() -> new NewOrder(UUID.randomUUID(), ACCOUNT, Side.BUY,
                OrderType.LIMIT, TimeInForce.DAY, Ticks.NO_PRICE, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LIMIT order requires a price");
    }

    @Test
    @DisplayName("a zero or negative quantity cannot be constructed")
    void quantityMustBePositive() {
        assertThatThrownBy(() -> new NewOrder(UUID.randomUUID(), ACCOUNT, Side.BUY,
                OrderType.LIMIT, TimeInForce.DAY, ticks("172.0000"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
