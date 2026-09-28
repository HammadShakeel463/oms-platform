package com.oms.matching.engine;

import com.oms.common.domain.OrderType;
import com.oms.common.domain.Side;
import com.oms.common.domain.TimeInForce;
import com.oms.common.money.Ticks;
import com.oms.matching.book.NewOrder;
import com.oms.matching.book.RecordingListener;
import com.oms.matching.config.EngineProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MatchingEngineTest {

    private static final long REFERENCE = Ticks.fromDecimal(new BigDecimal("172.0000"));

    private MatchingEngine engine;
    private RecordingListener listener;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine(new EngineProperties(10, 64, 4_096, 3));
        listener = new RecordingListener();
    }

    private NewOrder limit(Side side, long quantity, long price) {
        return new NewOrder(UUID.randomUUID(), "ACC-A", side, OrderType.LIMIT,
                TimeInForce.DAY, price, quantity);
    }

    @Test
    @DisplayName("a book is created on first sight of a symbol")
    void booksAreCreatedLazily() {
        assertThat(engine.bookCount()).isZero();

        engine.submit("HBL", limit(Side.BUY, 100, REFERENCE), listener);

        assertThat(engine.bookCount()).isEqualTo(1);
        assertThat(engine.symbols()).containsExactly("HBL");
    }

    @Test
    @DisplayName("books are independent: an order in one symbol cannot touch another")
    void booksAreIndependent() {
        engine.submit("HBL", limit(Side.SELL, 100, REFERENCE), listener);
        engine.submit("OGDC", limit(Side.BUY, 100, REFERENCE), listener);

        assertThat(listener.trades())
                .as("a buy in OGDC must not cross a sell in HBL at the same price")
                .isEmpty();
        assertThat(engine.bookCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a replayed order is ignored - this is what makes at-least-once safe")
    void duplicateOrdersAreIgnored() {
        NewOrder resting = limit(Side.SELL, 100, REFERENCE);
        NewOrder aggressor = limit(Side.BUY, 100, REFERENCE);

        engine.submit("HBL", resting, listener);
        assertThat(engine.submit("HBL", aggressor, listener)).isTrue();
        assertThat(listener.totalTradedQuantity()).isEqualTo(100);

        // Kafka redelivers the same accepted event. Matching it again would create 100
        // shares of volume out of nothing.
        assertThat(engine.submit("HBL", aggressor, listener)).isFalse();
        assertThat(listener.totalTradedQuantity()).isEqualTo(100);
    }

    @Test
    @DisplayName("a replayed resting order is not double-booked")
    void duplicateRestingOrderIsIgnored() {
        NewOrder order = limit(Side.BUY, 100, REFERENCE);

        engine.submit("HBL", order, listener);
        engine.submit("HBL", order, listener);

        assertThat(engine.slot("HBL").book().liveOrderCount()).isEqualTo(1);
        assertThat(engine.slot("HBL").book().liveQuantity()).isEqualTo(100);
    }

    @Test
    @DisplayName("deduplication is bounded, and old ids do eventually fall out")
    void dedupeIsBounded() {
        // Capacity 64 in this test. An id evicted after enough intervening orders would be
        // reprocessed - a deliberate trade against unbounded memory, documented in BookSlot.
        NewOrder first = limit(Side.BUY, 100, REFERENCE - 10_000);
        engine.submit("HBL", first, listener);

        for (int i = 0; i < 200; i++) {
            engine.submit("HBL", limit(Side.BUY, 1, REFERENCE - 20_000 - i), listener);
        }

        assertThat(engine.submit("HBL", first, listener))
                .as("evicted from the bounded dedupe window, so accepted again")
                .isTrue();
    }

    @Test
    @DisplayName("cancels are not deduplicated: a second cancel answers UNKNOWN_ORDER")
    void cancelsAreNotDeduplicated() {
        NewOrder order = limit(Side.BUY, 100, REFERENCE);
        engine.submit("HBL", order, listener);

        assertThat(engine.cancel("HBL", order.orderId(), "ACC-A", listener)).isTrue();
        assertThat(engine.cancel("HBL", order.orderId(), "ACC-A", listener)).isFalse();
        assertThat(listener.cancels()).hasSize(2);
    }

    @Test
    @DisplayName("a snapshot is only visible to readers after it is published")
    void snapshotsArePublishedExplicitly() {
        engine.submit("HBL", limit(Side.BUY, 100, REFERENCE), listener);

        assertThat(engine.snapshot("HBL").bids())
                .as("publication is per batch, not per order")
                .isEmpty();

        engine.publishSnapshot("HBL");

        assertThat(engine.snapshot("HBL").bids()).hasSize(1);
        assertThat(engine.snapshot("HBL").bestBidTicks()).isEqualTo(REFERENCE);
    }

    @Test
    @DisplayName("an unknown symbol reads as an empty book and creates nothing")
    void unknownSymbolDoesNotCreateABook() {
        assertThat(engine.snapshot("NOSUCH").bids()).isEmpty();
        assertThat(engine.snapshot("NOSUCH").bestBidTicks()).isEqualTo(Ticks.NO_PRICE);
        assertThat(engine.bookCount())
                .as("a depth query must not be able to make the engine allocate a book")
                .isZero();
    }

    @Test
    @DisplayName("publishing a snapshot for an unknown symbol is a no-op, not a failure")
    void publishingUnknownSymbolIsSafe() {
        engine.publishSnapshot("NOSUCH");

        assertThat(engine.bookCount()).isZero();
    }

    @Test
    @DisplayName("resetting a symbol clears the book and the dedupe window")
    void resetClearsTheBook() {
        NewOrder order = limit(Side.BUY, 100, REFERENCE);
        engine.submit("HBL", order, listener);
        engine.publishSnapshot("HBL");

        engine.resetSymbol("HBL");

        assertThat(engine.slot("HBL").book().liveOrderCount()).isZero();
        assertThat(engine.snapshot("HBL").bids()).isEmpty();
        assertThat(engine.submit("HBL", order, listener))
                .as("after a reset the engine has no memory of the order, so a replay "
                        + "rebuilds rather than being suppressed - which is exactly what a "
                        + "partition reassignment needs")
                .isTrue();
    }

    @Test
    @DisplayName("concurrent first-touch of the same symbol still creates exactly one book")
    void slotCreationIsRaceFree() throws Exception {
        int threads = 8;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var latch = new java.util.concurrent.CountDownLatch(1);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                latch.await();
                return engine.slot("HBL");
            });
        }
        latch.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(engine.bookCount())
                .as("computeIfAbsent on a ConcurrentHashMap guarantees one book per symbol; "
                        + "a plain HashMap here would be a genuine corruption risk")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("EngineProperties falls back to sane values rather than starting misconfigured")
    void propertiesHaveDefaults() {
        EngineProperties properties = new EngineProperties(0, 0, -1, 0);

        assertThat(properties.snapshotDepth()).isEqualTo(10);
        assertThat(properties.dedupeCapacity()).isEqualTo(65_536);
        assertThat(properties.nodePoolLimit()).isEqualTo(4_096);
        assertThat(properties.concurrency()).isEqualTo(3);
        assertThat(Duration.ZERO).isNotNull();
    }
}
