package com.oms.order.risk;

import com.oms.common.error.RiskRejectedException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Runs every {@link RiskCheck} in order and fails on the first rejection.
 *
 * <p>The injected {@code List<RiskCheck>} is Spring collecting every bean of that type,
 * sorted by {@code @Order}. Two things about that are worth knowing before defending it:
 *
 * <ul>
 *   <li>Constructor injection, not field injection. The dependency is final, the object is
 *       fully constructed or not constructed at all, and the class can be instantiated in a
 *       unit test with {@code new RiskEngine(List.of(check), registry)} - no Spring, no
 *       reflection. Field injection with {@code @Autowired} gives up all three.</li>
 *   <li>The list is ordered, and the order is part of the design: cheap checks first, and
 *       the checks that need the instrument before the ones that need account state, so a
 *       rejected order does the least work. Without {@code @Order} the list order is
 *       effectively classpath order, which is stable until it silently is not.</li>
 * </ul>
 *
 * <p>Fail-fast rather than collect-all-violations is deliberate for risk: the first breach
 * is the reason the order is refused, and evaluating later checks against an order already
 * known to be invalid produces misleading messages.
 */
@Component
public class RiskEngine {

    private static final Logger log = LoggerFactory.getLogger(RiskEngine.class);

    private final List<RiskCheck> checks;
    private final MeterRegistry meterRegistry;

    public RiskEngine(List<RiskCheck> checks, MeterRegistry meterRegistry) {
        this.checks = List.copyOf(checks);
        this.meterRegistry = meterRegistry;
        log.info("Pre-trade risk suite active with {} checks: {}",
                checks.size(), checks.stream().map(RiskCheck::name).toList());
    }

    /**
     * @throws RiskRejectedException on the first failing check
     */
    public void evaluate(RiskContext context) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            for (RiskCheck check : checks) {
                try {
                    check.check(context);
                } catch (RiskRejectedException rejected) {
                    meterRegistry.counter("oms.risk.rejections",
                            "check", rejected.check(),
                            "symbol", context.order().getSymbol()).increment();
                    log.info("Risk rejected order {} on check {}: {}",
                            context.order().getOrderId(), rejected.check(), rejected.getMessage());
                    throw rejected;
                }
            }
            meterRegistry.counter("oms.risk.passed").increment();
        } finally {
            sample.stop(meterRegistry.timer("oms.risk.evaluation"));
        }
    }

    /** Exposed for the startup log and for tests that assert the suite composition. */
    public List<String> checkNames() {
        return checks.stream().map(RiskCheck::name).toList();
    }
}
