package com.oms.order.risk.checks;

import com.oms.common.domain.OrderType;
import com.oms.common.error.RiskRejectedException;
import com.oms.common.money.Ticks;
import com.oms.order.risk.RiskCheck;
import com.oms.order.risk.RiskContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * A limit price must sit on the instrument tick grid. Market orders have no price of their
 * own, so this check does not apply to them.
 */
@Component
@Order(30)
public class TickSizeCheck implements RiskCheck {

    @Override
    public String name() {
        return "TICK_SIZE";
    }

    @Override
    public void check(RiskContext ctx) {
        if (ctx.order().getOrderType() != OrderType.LIMIT) {
            return;
        }
        long tick = ctx.instrument().tickSizeTicks();
        long price = ctx.order().limitPriceTicks();
        if (tick > 0 && price % tick != 0) {
            throw new RiskRejectedException(name(),
                    "Limit price " + Ticks.format(price) + " is not a multiple of the tick size "
                            + Ticks.format(tick) + " for " + ctx.instrument().symbol());
        }
    }
}
