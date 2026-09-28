package com.oms.common.event;

import com.oms.common.domain.OrderType;
import com.oms.common.domain.Side;
import com.oms.common.domain.TimeInForce;

import java.time.Instant;
import java.util.UUID;

/**
 * order-service -> matching-engine: an order has passed validation and risk and is
 * cleared to be worked. Topic {@link Topics#ORDERS_ACCEPTED}, keyed by symbol so that
 * every order for one book lands in one partition and is therefore worked in order.
 *
 * @param limitPriceTicks fixed-point limit price, or {@code Ticks.NO_PRICE} for MARKET
 * @param quantity        share count, always positive; side carries the direction
 */
public record OrderAcceptedEvent(
        String eventId,
        Instant occurredAt,
        int schemaVersion,
        UUID orderId,
        String clientOrderId,
        String accountId,
        String symbol,
        Side side,
        OrderType orderType,
        TimeInForce timeInForce,
        long limitPriceTicks,
        long quantity
) implements DomainEvent {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * Canonical constructor doubles as the invariant gate. A record gives you the
     * fields, {@code equals}, {@code hashCode} and {@code toString} for free, but
     * validity is still yours to enforce - and enforcing it here means an invalid
     * event cannot exist, not even transiently.
     */
    public OrderAcceptedEvent {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, was " + quantity);
        }
        if (orderId == null || symbol == null || accountId == null
                || side == null || orderType == null || timeInForce == null) {
            throw new IllegalArgumentException("mandatory field missing on OrderAcceptedEvent");
        }
    }

    @Override
    public String partitionKey() {
        return symbol;
    }
}
