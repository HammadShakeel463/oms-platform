package com.oms.order.risk.checks;

import com.oms.common.error.RiskRejectedException;
import com.oms.common.money.Ticks;
import com.oms.order.risk.RiskCheck;
import com.oms.order.risk.RiskContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Notional value of this single order against the account limit. */
@Component
@Order(50)
public class MaxOrderValueCheck implements RiskCheck {

    @Override
    public String name() {
        return "MAX_ORDER_VALUE";
    }

    @Override
    public void check(RiskContext ctx) {
        BigDecimal notional;
        try {
            notional = Ticks.notional(ctx.order().getQuantity(), ctx.effectivePriceTicks());
        } catch (ArithmeticException overflow) {
            // Math.multiplyExact refused the multiplication. An order whose notional does
            // not fit in a long is, by construction, larger than any limit - and this is
            // exactly the case where silent wraparound would produce a NEGATIVE notional
            // that passes an upper-bound check. Translate it into a normal rejection.
            throw new RiskRejectedException(name(),
                    "Order notional overflows: quantity " + ctx.order().getQuantity()
                            + " at price " + Ticks.format(ctx.effectivePriceTicks()));
        }

        BigDecimal limit = ctx.account().getMaxOrderNotional();
        if (notional.compareTo(limit) > 0) {
            throw new RiskRejectedException(name(),
                    "Order notional " + notional.toPlainString() + " exceeds the account limit "
                            + limit.toPlainString() + " for " + ctx.account().getAccountId());
        }
    }
}
