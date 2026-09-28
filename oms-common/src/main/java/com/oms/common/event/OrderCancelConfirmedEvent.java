package com.oms.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * matching-engine -> order-service: residual quantity has been removed from the book.
 * Topic {@link Topics#EXECUTION_REPORTS}, keyed by symbol.
 *
 * <p>The engine is the only component that knows whether a cancel actually landed, so
 * order-service does not move an order to CANCELLED on the client request alone - it
 * waits for this confirmation. That is the difference between an order book and a
 * database pretending to be one.
 *
 * @param sequence engine sequence number for this symbol; strictly increasing
 */
public record OrderCancelConfirmedEvent(
        String eventId,
        Instant occurredAt,
        int schemaVersion,
        UUID orderId,
        String symbol,
        String accountId,
        CancelReason reason,
        long cancelledQuantity,
        long sequence
) implements DomainEvent {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    @Override
    public String partitionKey() {
        return symbol;
    }
}
