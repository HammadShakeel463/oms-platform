package com.oms.common.marketdata;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Latest known quote for a symbol, as returned by the market-data snapshot REST endpoint
 * and cached in Redis.
 *
 * <p>Decimal at this boundary, not ticks: this is a client-facing payload, and a JSON
 * number that is exactly {@code 172.4500} is worth more than one that saves four bytes.
 */
public record QuoteSnapshot(
        String symbol,
        BigDecimal bid,
        long bidSize,
        BigDecimal ask,
        long askSize,
        BigDecimal last,
        long lastSize,
        long sequence,
        Instant asOf
) {
}
