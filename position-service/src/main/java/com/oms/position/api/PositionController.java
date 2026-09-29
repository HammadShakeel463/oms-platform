package com.oms.position.api;

import com.oms.position.api.dto.AccountPnlResponse;
import com.oms.position.api.dto.PositionResponse;
import com.oms.position.domain.PositionEntity;
import com.oms.position.service.PositionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Positions and P&amp;L.
 *
 * <p>{@code X-Account-Id} is the gateway-injected account, replaced by a verified JWT claim in
 * Phase 5. Read in one place, as in order-service, so the swap is a one-line change.
 */
@RestController
@RequestMapping("/api/v1")
public class PositionController {

    private static final int MONEY_SCALE = 4;

    private final PositionService positionService;
    private final Clock clock;

    public PositionController(PositionService positionService, Clock clock) {
        this.positionService = positionService;
        this.clock = clock;
    }

    @GetMapping("/positions")
    public List<PositionResponse> positions(@RequestHeader("X-Account-Id") String accountId,
                                            @RequestParam(defaultValue = "true") boolean openOnly) {
        return positionService.positionsFor(accountId, openOnly).stream()
                .map(position -> PositionResponse.from(position,
                        positionService.markFor(position.getSymbol()).orElse(null)))
                .toList();
    }

    @GetMapping("/positions/{symbol}")
    public PositionResponse position(@RequestHeader("X-Account-Id") String accountId,
                                     @PathVariable String symbol) {
        String upper = symbol.toUpperCase();
        PositionEntity position = positionService.positionFor(accountId, upper);
        return PositionResponse.from(position, positionService.markFor(upper).orElse(null));
    }

    /**
     * Account P&amp;L summary.
     *
     * <p>The {@code complete} flag and {@code unmarkedSymbols} list are the point of this endpoint.
     * Summing unrealised P&amp;L across positions is only meaningful if every open position has a
     * mark; if one does not, the total is understated. Reporting the gap explicitly means a caller
     * can decide whether to trust the number, rather than being handed a figure that looks
     * authoritative and is not.
     */
    @GetMapping("/pnl")
    public AccountPnlResponse pnl(@RequestHeader("X-Account-Id") String accountId) {
        List<PositionEntity> positions = positionService.positionsFor(accountId, false);

        BigDecimal realised = BigDecimal.ZERO;
        BigDecimal unrealised = BigDecimal.ZERO;
        BigDecimal grossExposure = BigDecimal.ZERO;
        BigDecimal netExposure = BigDecimal.ZERO;
        List<String> unmarked = new ArrayList<>();
        int open = 0;

        for (PositionEntity position : positions) {
            realised = realised.add(position.getRealisedPnl());

            if (position.isFlat()) {
                continue;
            }
            open++;

            Optional<BigDecimal> mark = positionService.markFor(position.getSymbol());
            if (mark.isEmpty()) {
                unmarked.add(position.getSymbol());
                continue;
            }

            BigDecimal positionUnrealised = position.unrealisedPnl(mark.get());
            if (positionUnrealised != null) {
                unrealised = unrealised.add(positionUnrealised);
            }

            BigDecimal value = position.marketValue(mark.get());
            netExposure = netExposure.add(value);
            grossExposure = grossExposure.add(value.abs());
        }

        boolean complete = unmarked.isEmpty();

        return new AccountPnlResponse(
                accountId,
                realised.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                unrealised.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                realised.add(unrealised).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                grossExposure.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                netExposure.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                open,
                List.copyOf(unmarked),
                complete,
                clock.instant());
    }
}
