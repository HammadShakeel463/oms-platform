package com.oms.matching.book;

import com.oms.common.domain.OrderType;
import com.oms.common.domain.Side;
import com.oms.common.domain.TimeInForce;
import com.oms.common.event.CancelReason;
import com.oms.common.money.Ticks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Randomised stress test against the invariants that must hold no matter what arrives.
 *
 * <p>Example-based tests check the cases you thought of. These check the cases you did not:
 * tens of thousands of random orders, cancels, market orders and IOCs, then two assertions
 * that no correct book can ever violate.
 *
 * <p><b>Invariant 1 - the book is never crossed.</b> If the best bid were at or above the best
 * ask, those two orders would have traded. Nearly every matching bug shows up here: a level
 * left in the tree after emptying, a sweep that stops one level early, an off-by-one in the
 * price comparison.
 *
 * <p><b>Invariant 2 - quantity is conserved.</b> Every share submitted is accounted for:
 * <pre>
 *   submitted = 2 x traded + cancelled + resting
 * </pre>
 * The factor of two is the whole point - a trade consumes quantity from an aggressor
 * <em>and</em> from a resting order. This catches the bugs that matter commercially: quantity
 * created from nothing, or quantity that silently disappears. Either one is money.
 *
 * <p>Seeded, so a failure is reproducible. A random test that cannot be replayed is a rumour.
 */
class BookInvariantTest {

    private static final String SYMBOL = "HBL";
    private static final int OPERATIONS = 20_000;

    /** Prices cluster around a reference, as real order flow does. */
    private static final long REFERENCE = Ticks.fromDecimal(new java.math.BigDecimal("172.0000"));
    private static final long TICK = Ticks.fromDecimal(new java.math.BigDecimal("0.0100"));

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1L, 42L, 1337L, 20260929L})
    @DisplayName("the tuned book never crosses and never loses or creates quantity")
    void tunedBookHoldsItsInvariants(long seed) {
        runStress(new PriceTimeOrderBook(SYMBOL), seed);
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1L, 42L})
    @DisplayName("the baseline book holds the same invariants, so the comparison is fair")
    void baselineBookHoldsTheSameInvariants(long seed) {
        runStress(new NaiveOrderBook(SYMBOL), seed);
    }

    private void runStress(LimitOrderBook book, long seed) {
        Random random = new Random(seed);
        RecordingListener listener = new RecordingListener();
        List<UUID> resting = new ArrayList<>();
        List<String> owners = new ArrayList<>();

        long submitted = 0;

        for (int i = 0; i < OPERATIONS; i++) {
            int roll = random.nextInt(100);

            if (roll < 30 && !resting.isEmpty()) {
                // 30% cancels: on a real venue cancels outnumber executions comfortably.
                int index = random.nextInt(resting.size());
                UUID orderId = resting.remove(index);
                String owner = owners.remove(index);
                book.cancel(orderId, owner, listener);
                continue;
            }

            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            long quantity = 1 + random.nextInt(1_000);
            String account = "ACC-" + random.nextInt(8);
            submitted += quantity;

            NewOrder order;
            if (roll < 35) {
                order = new NewOrder(UUID.randomUUID(), account, side, OrderType.MARKET,
                        TimeInForce.IOC, Ticks.NO_PRICE, quantity);
            } else {
                // Prices within +/- 50 ticks of the reference, so both sides genuinely meet.
                long price = REFERENCE + (random.nextInt(101) - 50) * TICK;
                TimeInForce tif = switch (random.nextInt(10)) {
                    case 0 -> TimeInForce.IOC;
                    case 1 -> TimeInForce.FOK;
                    default -> TimeInForce.DAY;
                };
                order = new NewOrder(UUID.randomUUID(), account, side, OrderType.LIMIT,
                        tif, price, quantity);
            }

            long liveBefore = book.liveOrderCount();
            book.submit(order, listener);

            // Track what rested, so cancels target real orders. A DAY order that was not
            // fully filled is now on the book.
            if (order.timeInForce() == TimeInForce.DAY
                    && order.type() == OrderType.LIMIT
                    && book.liveOrderCount() > liveBefore) {
                resting.add(order.orderId());
                owners.add(account);
            }

            // Invariant 1, checked continuously rather than only at the end: a book that
            // crosses transiently and then recovers is still broken, and only a per-step
            // check will catch it.
            assertThat(book.snapshot(50).isCrossed())
                    .as("book crossed after operation %d (seed %d)", i, seed)
                    .isFalse();
        }

        long traded = listener.totalTradedQuantity();
        long cancelled = listener.cancels().stream()
                // UNKNOWN_ORDER carries quantity 0 and removes nothing, so it does not
                // participate in the accounting.
                .filter(c -> c.reason() != CancelReason.UNKNOWN_ORDER)
                .mapToLong(RecordingListener.Cancelled::quantity)
                .sum();
        long stillResting = book.liveQuantity();

        assertThat(2 * traded + cancelled + stillResting)
                .as("quantity conservation (seed %d): submitted=%d traded=%d cancelled=%d resting=%d",
                        seed, submitted, traded, cancelled, stillResting)
                .isEqualTo(submitted);

        // Sanity: the test itself has to have done some work, or it proves nothing.
        assertThat(traded).isPositive();
        assertThat(listener.cancels()).isNotEmpty();
    }
}
