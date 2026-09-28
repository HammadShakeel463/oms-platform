package com.oms.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * order-service -> matching-engine: a cancel has been requested for a live order.
 * Topic {@link Topics#ORDERS_CANCEL_REQUESTS}, keyed by symbol.
 *
 * <p>Keying by symbol (not by order id) is deliberate: the cancel must be serialised
 * against the new-order stream for the same book, otherwise a cancel can overtake the
 * order it cancels. Same partition, same consumer thread, same order - a total order
 * per book without a single lock.
 */
public record OrderCancelRequestedEvent(
        String eventId,
        Instant occurredAt,
        int schemaVersion,
        UUID orderId,
        String symbol,
        String accountId,
        String requestedBy
) implements DomainEvent {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    @Override
    public String partitionKey() {
        return symbol;
    }
}
