package com.oms.position.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Account-level P and L summary.
 *
 * <p>{@code unmarkedSymbols} exists so the totals can be trusted. If any open position has no mark,
 * the unrealised figure is incomplete - and the honest thing is to say which symbols are missing
 * rather than quietly summing the ones that happen to be available. A total that silently omits a
 * position is worse than no total.
 */
public record AccountPnlResponse(
        String accountId,
        BigDecimal realisedPnl,
        BigDecimal unrealisedPnl,
        BigDecimal totalPnl,
        BigDecimal grossExposure,
        BigDecimal netExposure,
        int openPositions,
        List<String> unmarkedSymbols,
        boolean complete,
        Instant asOf
) {
}
