package com.oms.bench;

import com.oms.common.domain.OrderType;
import com.oms.common.domain.Side;
import com.oms.common.domain.TimeInForce;
import com.oms.common.money.Ticks;
import com.oms.matching.book.NewOrder;

import java.math.BigDecimal;
import java.util.Random;
import java.util.UUID;

/**
 * A pre-generated stream of book operations.
 *
 * <p><b>Why it is pre-generated.</b> Everything the benchmark can do before the timer starts,
 * it does before the timer starts. Generating an order inside the measured region would put
 * {@code UUID.randomUUID()} - which reads from {@code SecureRandom} and costs on the order of a
 * microsecond - inside a measurement whose target is tens of nanoseconds. The benchmark would
 * then be a very precise measurement of the random number generator.
 *
 * <p>UUIDs here come from a seeded {@link Random} via {@code new UUID(long, long)} rather than
 * {@code randomUUID()}: fast, and deterministic, so two runs replay the identical stream and a
 * before/after comparison is like-for-like.
 *
 * <p><b>Why the mix looks like this.</b> A benchmark that only inserts orders measures
 * insertion, which is not what an order book spends its time doing. On a real venue most
 * messages never trade: quotes are posted and pulled continuously, and cancels comfortably
 * outnumber executions. The mix below is deliberately closer to that shape than to a
 * best-case sweep:
 *
 * <ul>
 *   <li>45% passive limit orders - rest on the book, no trade;</li>
 *   <li>25% cancels of an order submitted a fixed distance back;</li>
 *   <li>25% aggressive limit orders - cross and trade against the resting side;</li>
 *   <li>5% market orders - sweep whatever is there.</li>
 * </ul>
 *
 * <p>The balance of passive orders and cancels keeps the book at a roughly steady depth for the
 * whole run, which matters: a workload that grows the book without bound measures a tree that
 * is getting deeper rather than a book doing its job, and the numbers drift upward for the
 * whole measurement.
 */
public final class Workload {

    /** Kinds of operation in the stream. */
    public static final int SUBMIT = 0;
    public static final int CANCEL = 1;

    private static final long REFERENCE = Ticks.fromDecimal(new BigDecimal("172.0000"));
    private static final long TICK = Ticks.fromDecimal(new BigDecimal("0.0100"));

    /** How far back a cancel reaches. Large enough that the target is usually still resting. */
    private static final int CANCEL_LAG = 96;

    public final int[] kind;
    public final NewOrder[] orders;
    /** For a CANCEL op: the index in {@code orders} whose id should be cancelled. */
    public final int[] cancelTarget;
    public final int length;

    private Workload(int length) {
        this.length = length;
        this.kind = new int[length];
        this.orders = new NewOrder[length];
        this.cancelTarget = new int[length];
    }

    public static Workload generate(int length, long seed) {
        Workload workload = new Workload(length);
        Random random = new Random(seed);

        for (int i = 0; i < length; i++) {
            int roll = random.nextInt(100);

            if (roll < 25 && i > CANCEL_LAG) {
                workload.kind[i] = CANCEL;
                // Walk back to an op that was a submit, so the cancel has a real target.
                int target = i - CANCEL_LAG;
                while (target > 0 && workload.kind[target] != SUBMIT) {
                    target--;
                }
                workload.cancelTarget[i] = target;
                workload.orders[i] = workload.orders[target];
                continue;
            }

            workload.kind[i] = SUBMIT;
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            long quantity = 100L * (1 + random.nextInt(20));
            String account = ACCOUNTS[random.nextInt(ACCOUNTS.length)];
            UUID orderId = new UUID(random.nextLong(), random.nextLong());

            if (roll >= 95) {
                // Market order: sweeps.
                workload.orders[i] = new NewOrder(orderId, account, side, OrderType.MARKET,
                        TimeInForce.IOC, Ticks.NO_PRICE, quantity);
            } else if (roll >= 70) {
                // Aggressive limit: priced through the touch so it trades.
                long price = side == Side.BUY
                        ? REFERENCE + (5 + random.nextInt(15)) * TICK
                        : REFERENCE - (5 + random.nextInt(15)) * TICK;
                workload.orders[i] = new NewOrder(orderId, account, side, OrderType.LIMIT,
                        TimeInForce.DAY, price, quantity);
            } else {
                // Passive limit: priced away from the touch so it rests.
                long price = side == Side.BUY
                        ? REFERENCE - (1 + random.nextInt(25)) * TICK
                        : REFERENCE + (1 + random.nextInt(25)) * TICK;
                workload.orders[i] = new NewOrder(orderId, account, side, OrderType.LIMIT,
                        TimeInForce.DAY, price, quantity);
            }
        }
        return workload;
    }

    private static final String[] ACCOUNTS = {
            "ACC-MM-1", "ACC-MM-2", "ACC-TRADER-1", "ACC-TRADER-2",
            "ACC-TRADER-3", "ACC-FUND-1", "ACC-FUND-2", "ACC-RETAIL-1"
    };

    /**
     * Pre-seeds a book with resting depth, so the first measured operations meet a realistic
     * book rather than an empty one.
     *
     * <p>A cold empty book makes every implementation look identical - there is nothing to walk
     * and nothing to cancel. The depth is where the differences live.
     */
    public static NewOrder[] restingDepth(int ordersPerSide, int levelsPerSide, long seed) {
        Random random = new Random(seed);
        NewOrder[] seed0 = new NewOrder[ordersPerSide * 2];
        int index = 0;

        for (int level = 1; level <= levelsPerSide; level++) {
            int perLevel = Math.max(1, ordersPerSide / levelsPerSide);
            for (int n = 0; n < perLevel && index < seed0.length - 1; n++) {
                long bid = REFERENCE - level * TICK;
                long ask = REFERENCE + level * TICK;
                long quantity = 100L * (1 + random.nextInt(10));
                seed0[index++] = new NewOrder(new UUID(random.nextLong(), random.nextLong()),
                        "ACC-MM-1", Side.BUY, OrderType.LIMIT, TimeInForce.DAY, bid, quantity);
                seed0[index++] = new NewOrder(new UUID(random.nextLong(), random.nextLong()),
                        "ACC-MM-2", Side.SELL, OrderType.LIMIT, TimeInForce.DAY, ask, quantity);
            }
        }

        // Trim in case the loop bounds left the tail null.
        NewOrder[] trimmed = new NewOrder[index];
        System.arraycopy(seed0, 0, trimmed, 0, index);
        return trimmed;
    }
}
