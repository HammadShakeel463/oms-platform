package com.oms.common.event;

import com.oms.common.domain.OrderStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * order-service -> anyone interested: an order changed state.
 * Topic {@link Topics#ORDERS_LIFECYCLE}, keyed by order id.
 *
 * <p>This is the audit stream. It is emitted from the same transaction that writes the
 * audit row (transactional outbox, see order-service), so the database history and the
 * topic cannot disagree. Keyed by order id because the only ordering that matters is
 * per-order, and that key gives a compaction-friendly stream if we ever want the latest
 * state per order as a table.
 *
 * @param version monotonic per-order revision, starting at 1 for NEW
 */
public record OrderLifecycleEvent(
        String eventId,
        Instant occurredAt,
        int schemaVersion,
        UUID orderId,
        String accountId,
        String symbol,
        OrderStatus previousStatus,
        OrderStatus newStatus,
        String reason,
        long filledQuantity,
        long leavesQuantity,
        long averagePriceTicks,
        int version
) implements DomainEvent {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    @Override
    public String partitionKey() {
        return orderId.toString();
    }
}
