package com.oms.marketdata.api;

import com.oms.common.event.MarketTickEvent;
import com.oms.common.marketdata.QuoteSnapshot;
import com.oms.common.money.Ticks;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A quote as a client sees it: decimals, not ticks (ADR 0002).
 *
 * <p>{@code sequence} is on the wire for a specific reason: it is how a streaming client detects
 * that it was conflated. A jump from 41 to 58 means sixteen updates were superseded before this one
 * was delivered. Conflation without a sequence number would be a silent lie; with one it is a
 * documented delivery model the client can reason about.
 */
public record QuoteResponse(
        String symbol,
        BigDecimal bid,
        long bidSize,
        BigDecimal ask,
        long askSize,
        BigDecimal last,
        long lastSize,
        BigDecimal mid,
        BigDecimal spread,
        long sequence,
        Instant asOf
) {

    public static QuoteResponse from(MarketTickEvent tick) {
        return new QuoteResponse(
                tick.symbol(),
                Ticks.toDecimal(tick.bidPriceTicks()), tick.bidSize(),
                Ticks.toDecimal(tick.askPriceTicks()), tick.askSize(),
                Ticks.toDecimal(tick.lastPriceTicks()), tick.lastSize(),
                Ticks.toDecimal(tick.midPriceTicks()),
                Ticks.toDecimal(tick.spreadTicks()),
                tick.sequence(),
                tick.occurredAt());
    }

    public static QuoteResponse from(QuoteSnapshot snapshot) {
        BigDecimal mid = snapshot.bid() == null || snapshot.ask() == null
                ? null
                : snapshot.bid().add(snapshot.ask())
                        .divide(BigDecimal.valueOf(2), Ticks.SCALE, java.math.RoundingMode.HALF_UP);
        BigDecimal spread = snapshot.bid() == null || snapshot.ask() == null
                ? null
                : snapshot.ask().subtract(snapshot.bid());

        return new QuoteResponse(
                snapshot.symbol(),
                snapshot.bid(), snapshot.bidSize(),
                snapshot.ask(), snapshot.askSize(),
                snapshot.last(), snapshot.lastSize(),
                mid, spread,
                snapshot.sequence(), snapshot.asOf());
    }
}
