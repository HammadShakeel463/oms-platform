package com.oms.order.risk.checks;

import com.oms.common.error.RiskRejectedException;
import com.oms.order.risk.RiskCheck;
import com.oms.order.risk.RiskContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Worst-case position limit.
 *
 * <p>The check is not "where is this account now" but "where could this order put it".
 * Current net position plus every working order on the same side plus this order is the
 * exposure the account is already committed to; checking only the filled position would let
 * an account place ten orders that individually pass and collectively breach the limit ten
 * times over. This is the single most common way a naive risk check is wrong.
 */
@Component
@Order(60)
public class MaxPositionCheck implements RiskCheck {

    @Override
    public String name() {
        return "MAX_POSITION";
    }

    @Override
    public void check(RiskContext ctx) {
        long limit = ctx.account().getMaxPositionQty();
        long worstCase = ctx.exposure()
                .worstCaseAfter(ctx.order().getSide(), ctx.order().getQuantity());

        if (Math.abs(worstCase) > limit) {
            throw new RiskRejectedException(name(),
                    "Worst-case position " + worstCase + " in " + ctx.order().getSymbol()
                            + " exceeds the account limit of " + limit
                            + " (net " + ctx.exposure().getNetQuantity()
                            + ", working buy " + ctx.exposure().getWorkingBuyQty()
                            + ", working sell " + ctx.exposure().getWorkingSellQty() + ")");
        }
    }
}
