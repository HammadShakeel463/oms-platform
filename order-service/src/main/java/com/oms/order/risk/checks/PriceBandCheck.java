package com.oms.order.risk.checks;

import com.oms.common.error.RiskRejectedException;
import com.oms.common.money.Ticks;
import com.oms.order.risk.RiskCheck;
import com.oms.order.risk.RiskContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * The fat-finger check: reject a price too far from the instrument reference price.
 *
 * <p>This is the control that catches an extra zero. The classic loss is an order entered
 * at 10x or 0.1x the intended price, which on a thin book sweeps every level and prints
 * trades nobody meant. A percentage band around the reference price stops it at the door.
 *
 * <p>Arithmetic is done in ticks with integer comparison rather than in floating point:
 * {@code |price - ref| * 100 <= ref * bandPercent}, rearranged so there is no division and
 * no rounding step that could put a borderline order on the wrong side of the limit.
 */
@Component
@Order(40)
public class PriceBandCheck implements RiskCheck {

    @Override
    public String name() {
        return "PRICE_BAND";
    }

    @Override
    public void check(RiskContext ctx) {
        long reference = ctx.instrument().referencePriceTicks();
        long price = ctx.effectivePriceTicks();

        if (reference <= 0 || price == Ticks.NO_PRICE) {
            // No reference price means the band cannot be evaluated. Rejecting rather than
            // waving it through: an un-checkable fat-finger control is not a control.
            throw new RiskRejectedException(name(),
                    "No reference price available for " + ctx.instrument().symbol()
                            + "; cannot evaluate the price band");
        }

        BigDecimal bandPercent = ctx.instrument().priceBandPercent();
        long deviation = Math.abs(price - reference);

        // allowed = reference * bandPercent / 100, rounded down so the band never widens
        // through rounding.
        BigDecimal allowed = BigDecimal.valueOf(reference)
                .multiply(bandPercent)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);

        if (BigDecimal.valueOf(deviation).compareTo(allowed) > 0) {
            BigDecimal deviationPercent = BigDecimal.valueOf(deviation)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(reference), new MathContext(4));
            throw new RiskRejectedException(name(),
                    "Price " + Ticks.format(price) + " deviates " + deviationPercent.toPlainString()
                            + "% from the reference price " + Ticks.format(reference)
                            + ", outside the permitted band of " + bandPercent.toPlainString() + "%");
        }
    }
}
