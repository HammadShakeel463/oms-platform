package com.oms.common.event;

import com.oms.common.domain.Side;

import java.time.Instant;
import java.util.UUID;

/**
 * matching-engine -> order-service and position-service: two orders traded.
 * Topic {@link Topics#TRADES_EXECUTED}, keyed by symbol.
 *
 * <p>One event carries both sides of the trade. That is not a convenience: it makes the
 * fill atomic for consumers. A position-service that received a buy-side fill and a
 * sell-side fill as two messages could observe a half-applied trade, and a risk report
 * taken at that instant would not balance.
 *
 * <p>Why keyed by symbol rather than by account, when position-service aggregates per
 * account: realised P&amp;L under average-cost is order-dependent, so what actually has to
 * be ordered is the stream per (account, symbol) pair. Every trade in that pair carries
 * the same symbol, so it lands in the same partition, so symbol keying already gives the
 * ordering the P&amp;L math needs - and it avoids a repartition hop. Documented in
 * docs/kafka-event-design.md.
 *
 * @param priceTicks   execution price; always the resting order price (price improvement
 *                     accrues to the aggressor, which is standard for a continuous book)
 * @param aggressor    side of the incoming order that caused the match
 * @param sequence     engine sequence number for this symbol; strictly increasing, and
 *                     the tie-breaker consumers use to reject replays and detect gaps
 */
public record TradeExecutedEvent(
        String eventId,
        Instant occurredAt,
        int schemaVersion,
        UUID tradeId,
        String symbol,
        long priceTicks,
        long quantity,
        Side aggressor,
        UUID buyOrderId,
        String buyAccountId,
        UUID sellOrderId,
        String sellAccountId,
        long sequence
) implements DomainEvent {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public TradeExecutedEvent {
        if (quantity <= 0) {
            throw new IllegalArgumentException("trade quantity must be positive, was " + quantity);
        }
    }

    @Override
    public String partitionKey() {
        return symbol;
    }

    /** The order id on the given side of this trade. */
    public UUID orderIdFor(Side side) {
        return side == Side.BUY ? buyOrderId : sellOrderId;
    }

    /** The account on the given side of this trade. */
    public String accountIdFor(Side side) {
        return side == Side.BUY ? buyAccountId : sellAccountId;
    }
}
