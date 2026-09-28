package com.oms.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fixed-point price/notional arithmetic.
 *
 * <p>Prices cross the API and land in PostgreSQL as {@link BigDecimal} (exact decimal,
 * auditable, what accountants and JPA expect). Inside the matching engine they are
 * {@code long} tick counts at {@link #SCALE} decimal places, because the hot path must
 * not allocate: every {@code BigDecimal} operation produces a new object, and an order
 * book that allocates per price comparison turns into GC pressure that shows up directly
 * in the p99.
 *
 * <p>This class is the single conversion boundary. Convert once on the way in, once on
 * the way out, and keep the engine integral.
 */
public final class Ticks {

    /** Decimal places carried by a tick. 4dp covers equity tick sizes with room to spare. */
    public static final int SCALE = 4;

    /** Ticks per whole currency unit: 10^SCALE. */
    public static final long ONE = 10_000L;

    /** Sentinel for "no price" (market orders, empty book side). Never a valid price. */
    public static final long NO_PRICE = Long.MIN_VALUE;

    private Ticks() {
    }

    /**
     * Exact conversion of a decimal price to ticks.
     *
     * @throws IllegalArgumentException if {@code price} carries more precision than
     *                                  {@link #SCALE} can represent - silently rounding
     *                                  a client price is how you lose money invisibly.
     */
    public static long fromDecimal(BigDecimal price) {
        if (price == null) {
            return NO_PRICE;
        }
        try {
            return price.setScale(SCALE, RoundingMode.UNNECESSARY).unscaledValue().longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "price " + price.toPlainString() + " is not representable at scale " + SCALE, e);
        }
    }

    public static BigDecimal toDecimal(long ticks) {
        if (ticks == NO_PRICE) {
            return null;
        }
        return BigDecimal.valueOf(ticks, SCALE);
    }

    /**
     * Notional value of {@code quantity} shares at {@code priceTicks}, in ticks.
     *
     * <p>Overflow is checked rather than wrapped: a fat-finger quantity must surface as a
     * loud failure, not as a negative notional that slips past a risk limit. In C++ this
     * is the {@code __builtin_mul_overflow} discipline; Java spells it {@code Math.multiplyExact}.
     */
    public static long notionalTicks(long quantity, long priceTicks) {
        return Math.multiplyExact(quantity, priceTicks);
    }

    /** Notional as a decimal currency amount. */
    public static BigDecimal notional(long quantity, long priceTicks) {
        return toDecimal(notionalTicks(quantity, priceTicks));
    }

    /** Formats ticks for logs without allocating a BigDecimal on the hot path. */
    public static String format(long ticks) {
        if (ticks == NO_PRICE) {
            return "-";
        }
        long whole = ticks / ONE;
        long frac = Math.abs(ticks % ONE);
        return whole + "." + String.format("%0" + SCALE + "d", frac);
    }
}
