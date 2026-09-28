package com.oms.common.event;

import com.oms.common.money.Ticks;

import java.time.Instant;

/**
 * market-data-service -> subscribers: a top-of-book / last-trade update.
 * Topic {@link Topics#MARKET_DATA_TICKS}, keyed by symbol.
 *
 * <p>The highest-volume event in the platform and the one place where a conflating
 * (last-value-wins) delivery model is correct: a subscriber that falls behind wants the
 * freshest quote, not a faithful replay of every quote it missed. That asymmetry - ticks
 * are conflatable, trades are not - drives the backpressure design in Phase 4.
 *
 * @param sequence per-symbol sequence number, so a client can detect that it was conflated
 */
public record MarketTickEvent(
        String eventId,
        Instant occurredAt,
        int schemaVersion,
        String symbol,
        long bidPriceTicks,
        long bidSize,
        long askPriceTicks,
        long askSize,
        long lastPriceTicks,
        long lastSize,
        long sequence
) implements DomainEvent {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    @Override
    public String partitionKey() {
        return symbol;
    }

    /** Midpoint in ticks, or {@code Ticks#NO_PRICE} if either side is absent. */
    public long midPriceTicks() {
        if (bidPriceTicks == Ticks.NO_PRICE
                || askPriceTicks == Ticks.NO_PRICE) {
            return Ticks.NO_PRICE;
        }
        return (bidPriceTicks + askPriceTicks) / 2;
    }

    public long spreadTicks() {
        if (bidPriceTicks == Ticks.NO_PRICE
                || askPriceTicks == Ticks.NO_PRICE) {
            return Ticks.NO_PRICE;
        }
        return askPriceTicks - bidPriceTicks;
    }
}
