package com.oms.common.reference;

import java.math.BigDecimal;

/**
 * Read-only projection of an instrument, as served by market-data-service and consumed by
 * order-service over REST (then cached in Redis).
 *
 * <p>This is a DTO in a shared module, NOT a shared entity. The distinction matters and
 * interviewers probe it: sharing a contract couples services to a versioned payload that
 * changes on purpose; sharing a JPA entity couples them to each other schema, and you have
 * built a distributed monolith. oms-common has no JPA dependency at all, which makes the
 * rule mechanical rather than aspirational.
 *
 * @param lotSize          minimum tradeable increment of quantity
 * @param tickSizeTicks    minimum price increment, in fixed-point ticks
 * @param priceBandPercent fat-finger band around {@code referencePriceTicks}, e.g. 10.00 = +/-10%
 */
public record InstrumentView(
        String symbol,
        String name,
        String isin,
        String currency,
        long lotSize,
        long tickSizeTicks,
        BigDecimal priceBandPercent,
        long referencePriceTicks,
        InstrumentStatus status
) {

    public boolean isTradeable() {
        return status == InstrumentStatus.ACTIVE;
    }
}
