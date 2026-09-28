package com.oms.order.risk.checks;

import com.oms.common.error.RiskRejectedException;
import com.oms.order.risk.RiskCheck;
import com.oms.order.risk.RiskContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Quantity must be a whole number of lots for this instrument. */
@Component
@Order(20)
public class LotSizeCheck implements RiskCheck {

    @Override
    public String name() {
        return "LOT_SIZE";
    }

    @Override
    public void check(RiskContext ctx) {
        long lot = ctx.instrument().lotSize();
        if (lot > 1 && ctx.order().getQuantity() % lot != 0) {
            throw new RiskRejectedException(name(),
                    "Quantity " + ctx.order().getQuantity() + " is not a multiple of the lot size "
                            + lot + " for " + ctx.instrument().symbol());
        }
    }
}
