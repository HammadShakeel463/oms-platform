package com.oms.matching.engine;

import com.oms.common.domain.OrderType;
import com.oms.common.domain.Side;
import com.oms.common.domain.TimeInForce;
import com.oms.common.money.Ticks;
import com.oms.matching.book.BookSnapshot;
import com.oms.matching.book.MatchListener;
import com.oms.matching.book.NewOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the reader/writer design: one writer mutating a book while several readers hammer
 * the published snapshot, with no lock anywhere.
 *
 * <p><b>What this test can and cannot prove.</b> It cannot prove the absence of a data race -
 * no test can, because a race is a property of the memory model rather than of an execution,
 * and a broken publication can pass a million runs on x86 and fail on ARM. What it does prove
 * is the part that <em>is</em> testable: readers never observe a torn or self-inconsistent
 * snapshot, readers never block the writer, and the writer makes progress at full speed while
 * being read.
 *
 * <p>The correctness argument for the absence of a race is the JMM one, made in
 * {@link BookSlot}: a volatile write is a release, a volatile read is an acquire, so a reader
 * that sees the new reference sees a fully constructed object. This test is the empirical
 * companion to that argument, not a substitute for it. Exactly the same division of labour as
 * reasoning about {@code memory_order_acquire}/{@code release} in C++ and then running
 * ThreadSanitizer: the reasoning is the proof, the run is the sanity check. (Java's equivalent
 * of TSan is jcstress, which is the right tool for a serious concurrency claim and is noted in
 * docs/concurrency.md as the next step.)
 */
class SnapshotPublicationConcurrencyTest {

    private static final String SYMBOL = "HBL";
    private static final long REFERENCE = Ticks.fromDecimal(new BigDecimal("172.0000"));
    private static final long TICK = Ticks.fromDecimal(new BigDecimal("0.0100"));
    private static final int READERS = 4;
    private static final long RUN_MILLIS = 1_500;

    @Test
    @DisplayName("readers never see a torn snapshot while the writer mutates the book")
    void concurrentReadersNeverSeeATornSnapshot() throws Exception {
        BookSlot slot = new BookSlot(SYMBOL, 10, 1 << 16, 4_096);

        AtomicLong ordersProcessed = new AtomicLong();
        AtomicLong snapshotsRead = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(READERS + 1);
        CountDownLatch start = new CountDownLatch(1);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RUN_MILLIS);

        // --- the single writer -------------------------------------------------------
        pool.submit(() -> {
            try {
                start.await();
                Random random = new Random(7);
                List<UUID> resting = new java.util.ArrayList<>();
                int sinceSnapshot = 0;

                while (System.nanoTime() < deadline) {
                    if (random.nextInt(100) < 25 && !resting.isEmpty()) {
                        UUID id = resting.remove(random.nextInt(resting.size()));
                        slot.book().cancel(id, "ACC-W", MatchListener.NO_OP);
                    } else {
                        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
                        long price = REFERENCE + (random.nextInt(41) - 20) * TICK;
                        NewOrder order = new NewOrder(UUID.randomUUID(), "ACC-W", side,
                                OrderType.LIMIT, TimeInForce.DAY, price, 1 + random.nextInt(500));
                        int before = slot.book().liveOrderCount();
                        slot.book().submit(order, MatchListener.NO_OP);
                        if (slot.book().liveOrderCount() > before) {
                            resting.add(order.orderId());
                        }
                    }
                    ordersProcessed.incrementAndGet();

                    // Publication happens per batch in production; here, every 50 operations.
                    if (++sinceSnapshot == 50) {
                        slot.publishSnapshot();
                        sinceSnapshot = 0;
                    }
                }
                slot.publishSnapshot();
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        // --- the readers -------------------------------------------------------------
        for (int r = 0; r < READERS; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (System.nanoTime() < deadline) {
                        BookSnapshot snapshot = slot.currentSnapshot();
                        assertSelfConsistent(snapshot);
                        snapshotsRead.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        if (failure.get() != null) {
            throw new AssertionError("concurrent run failed", failure.get());
        }

        assertThat(ordersProcessed.get())
                .as("the writer must actually have done work")
                .isGreaterThan(10_000);
        assertThat(snapshotsRead.get())
                .as("the readers must actually have read")
                .isGreaterThan(10_000);
    }

    /**
     * Everything a snapshot must satisfy on its own, with no reference to the live book.
     *
     * <p>These are the assertions that would fail on a torn read: a bid list that is not
     * descending, a level with zero quantity, a best price that does not match the first
     * level, a crossed book.
     */
    private static void assertSelfConsistent(BookSnapshot snapshot) {
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.isCrossed())
                .as("a published snapshot must never be crossed: %s", snapshot)
                .isFalse();

        List<BookSnapshot.Level> bids = snapshot.bids();
        for (int i = 0; i < bids.size(); i++) {
            BookSnapshot.Level level = bids.get(i);
            assertThat(level.quantity()).as("bid level quantity").isPositive();
            assertThat(level.orderCount()).as("bid level order count").isPositive();
            if (i > 0) {
                assertThat(level.priceTicks())
                        .as("bids must be strictly descending")
                        .isLessThan(bids.get(i - 1).priceTicks());
            }
        }

        List<BookSnapshot.Level> asks = snapshot.asks();
        for (int i = 0; i < asks.size(); i++) {
            BookSnapshot.Level level = asks.get(i);
            assertThat(level.quantity()).as("ask level quantity").isPositive();
            assertThat(level.orderCount()).as("ask level order count").isPositive();
            if (i > 0) {
                assertThat(level.priceTicks())
                        .as("asks must be strictly ascending")
                        .isGreaterThan(asks.get(i - 1).priceTicks());
            }
        }

        if (!bids.isEmpty()) {
            assertThat(snapshot.bestBidTicks())
                    .as("bestBid must agree with the first bid level")
                    .isEqualTo(bids.get(0).priceTicks());
        }
        if (!asks.isEmpty()) {
            assertThat(snapshot.bestAskTicks())
                    .as("bestAsk must agree with the first ask level")
                    .isEqualTo(asks.get(0).priceTicks());
        }
    }

    @Test
    @DisplayName("a published snapshot is immutable, so a reader cannot corrupt the writer")
    void snapshotListsAreUnmodifiable() {
        BookSlot slot = new BookSlot(SYMBOL, 10, 1 << 16, 4_096);
        slot.book().submit(new NewOrder(UUID.randomUUID(), "ACC-A", Side.BUY, OrderType.LIMIT,
                TimeInForce.DAY, REFERENCE, 100), MatchListener.NO_OP);
        slot.publishSnapshot();

        BookSnapshot snapshot = slot.currentSnapshot();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> snapshot.bids().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a book with no published snapshot yet reads as empty, never null")
    void unpublishedSlotReadsEmpty() {
        BookSlot slot = new BookSlot(SYMBOL, 10, 1 << 16, 4_096);

        assertThat(slot.currentSnapshot()).isNotNull();
        assertThat(slot.currentSnapshot().bids()).isEmpty();
        assertThat(slot.currentSnapshot().bestBidTicks()).isEqualTo(Ticks.NO_PRICE);
    }
}
