package com.oms.marketdata.api;

import com.oms.common.error.NotFoundException;
import com.oms.marketdata.quote.QuoteCache;
import com.oms.marketdata.simulator.TickGenerator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Point-in-time quote snapshots.
 *
 * <p>Reads local state first, Redis second. The ordering is not an optimisation - it is a
 * correctness preference: this instance's own in-memory state is by definition at least as fresh as
 * what it last wrote to Redis, so preferring it means a client never receives a quote older than the
 * one the server already has.
 *
 * <p>If neither has it, the answer is 404 rather than a stale or fabricated price. A market data
 * service that invents a quote when it does not have one is the single most dangerous thing in this
 * platform - order-service prices market orders off this endpoint.
 */
@RestController
@RequestMapping("/api/v1/quotes")
public class QuoteController {

    private final TickGenerator tickGenerator;
    private final QuoteCache quoteCache;

    public QuoteController(TickGenerator tickGenerator, QuoteCache quoteCache) {
        this.tickGenerator = tickGenerator;
        this.quoteCache = quoteCache;
    }

    @GetMapping("/{symbol}")
    public QuoteResponse quote(@PathVariable String symbol) {
        String upper = symbol.toUpperCase();
        return tickGenerator.liveSnapshot(upper)
                .or(() -> quoteCache.get(upper))
                .map(QuoteResponse::from)
                .orElseThrow(() -> NotFoundException.instrument(upper));
    }
}
