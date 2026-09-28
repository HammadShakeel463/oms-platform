package com.oms.matching.book;

import com.oms.common.domain.OrderType;
import com.oms.common.domain.Side;
import com.oms.common.domain.TimeInForce;
import com.oms.common.money.Ticks;

import java.util.UUID;

/**
 * An order arriving at the book.
 *
 * <p>Deliberately a separate type from {@code OrderAcceptedEvent}: the event is a wire
 * contract with schema versions and timestamps, this is what the matching loop needs and
 * nothing else. One allocation per incoming order, which is free - the Kafka deserialiser
 * already allocated an object for this message, so the conversion adds one short-lived
 * object per order and nothing in the inner loop.
 *
 * @param limitPriceTicks the client limit, or {@link Ticks#NO_PRICE} for a MARKET order
 */
public record NewOrder(
        UUID orderId,
        String accountId,
        Side side,
        OrderType type,
        TimeInForce timeInForce,
        long limitPriceTicks,
        long quantity
) {

    public NewOrder {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, was " + quantity);
        }
        if (type == OrderType.LIMIT && limitPriceTicks == Ticks.NO_PRICE) {
            throw new IllegalArgumentException("LIMIT order requires a price");
        }
    }

    /**
     * The price used for matching comparisons.
     *
     * <p>A market order is modelled as an infinitely aggressive limit: {@code MAX_VALUE} for
     * a buy, {@code MIN_VALUE + 1} for a sell. That removes the "is this a market order"
     * branch from the inner loop entirely - the loop only ever compares two longs, and
     * market orders fall out of the same comparison as limit orders.
     *
     * <p>{@code MIN_VALUE + 1} rather than {@code MIN_VALUE} because {@code MIN_VALUE} is
     * the {@link Ticks#NO_PRICE} sentinel; overlapping the two would make an absent price
     * indistinguishable from the most aggressive possible sell.
     */
    public long effectiveLimitTicks() {
        if (type == OrderType.LIMIT) {
            return limitPriceTicks;
        }
        return side == Side.BUY ? Long.MAX_VALUE : Long.MIN_VALUE + 1;
    }

    /** True when unfilled residual must not rest on the book. */
    public boolean isImmediate() {
        return type == OrderType.MARKET || timeInForce.isImmediate();
    }

    public boolean isFillOrKill() {
        return timeInForce == TimeInForce.FOK;
    }
}
