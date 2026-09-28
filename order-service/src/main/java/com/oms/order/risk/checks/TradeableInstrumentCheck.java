package com.oms.order.risk.checks;

import com.oms.common.error.RiskRejectedException;
import com.oms.order.risk.RiskCheck;
import com.oms.order.risk.RiskContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Cheapest check, so it runs first: is this instrument open for trading at all. */
@Component
@Order(10)
public class TradeableInstrumentCheck implements RiskCheck {

    @Override
    public String name() {
        return "INSTRUMENT_TRADEABLE";
    }

    @Override
    public void check(RiskContext ctx) {
        if (!ctx.instrument().isTradeable()) {
            throw new RiskRejectedException(name(),
                    "Instrument " + ctx.instrument().symbol() + " is "
                            + ctx.instrument().status() + " and cannot be traded");
        }
    }
}
