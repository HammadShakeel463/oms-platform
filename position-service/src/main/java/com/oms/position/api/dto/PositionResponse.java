package com.oms.position.api.dto;

import com.oms.position.domain.PositionEntity;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A position as a client sees it.
 *
 * <p>{@code unrealisedPnl}, {@code marketValue} and {@code markPrice} are nullable, and the null is
 * meaningful: it says "this symbol has no mark on this instance", which is a different statement
 * from "the unrealised P and L is zero". A risk screen that silently renders an unmarked position as
 * flat is exactly the bug that makes somebody think they have no exposure.
 */
public record PositionResponse(
        String accountId,
        String symbol,
        long netQuantity,
        BigDecimal averageCost,
        BigDecimal realisedPnl,
        BigDecimal markPrice,
        BigDecimal unrealisedPnl,
        BigDecimal marketValue,
        BigDecimal totalPnl,
        long boughtQuantity,
        long soldQuantity,
        int fillCount,
        Instant updatedAt
) {

    public static PositionResponse from(PositionEntity position, BigDecimal markPrice) {
        BigDecimal unrealised = position.unrealisedPnl(markPrice);
        BigDecimal total = unrealised == null
                ? null
                : position.getRealisedPnl().add(unrealised);

        return new PositionResponse(
                position.getAccountId(),
                position.getSymbol(),
                position.getNetQuantity(),
                position.averageCost(),
                position.getRealisedPnl(),
                markPrice,
                unrealised,
                position.marketValue(markPrice),
                total,
                position.getBoughtQuantity(),
                position.getSoldQuantity(),
                position.getFillCount(),
                position.getUpdatedAt());
    }
}
