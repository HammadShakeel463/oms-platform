package com.oms.marketdata.api;

import com.oms.common.reference.InstrumentView;
import com.oms.marketdata.reference.InstrumentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Reference data.
 *
 * <p>This is the endpoint order-service calls on the order path, so it is the only market-data
 * endpoint whose latency is on a trading critical path. It is served from a Redis cache, and
 * order-service caches the answer again on its own side - two layers, because the alternative is a
 * validation path whose availability depends on this service being up.
 *
 * <p>The response type is {@link InstrumentView} from {@code oms-common}: the shared contract, not
 * the JPA entity (ADR 0001).
 */
@RestController
@RequestMapping("/api/v1/instruments")
public class InstrumentController {

    private final InstrumentService instrumentService;

    public InstrumentController(InstrumentService instrumentService) {
        this.instrumentService = instrumentService;
    }

    @GetMapping
    public List<InstrumentView> list() {
        return instrumentService.findAll();
    }

    /**
     * @throws com.oms.common.error.NotFoundException rendered as 404 by the shared advice in
     *                                                {@code oms-web}
     */
    @GetMapping("/{symbol}")
    public InstrumentView bySymbol(@PathVariable String symbol) {
        return instrumentService.findBySymbol(symbol.toUpperCase());
    }
}
