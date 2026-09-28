package com.oms.order.risk;

import com.oms.common.reference.InstrumentView;
import com.oms.order.domain.AccountEntity;
import com.oms.order.domain.OrderEntity;
import com.oms.order.domain.PositionExposureEntity;

/**
 * Everything a pre-trade check needs, gathered once.
 *
 * <p>Passing a context object rather than letting each check fetch what it wants is a
 * deliberate constraint: it makes every check a pure function of its input, so each one is
 * unit-testable without a database, a cache or a mock, and it guarantees all checks see the
 * same consistent snapshot. A check that did its own lookup could evaluate against a
 * different instrument state than the check before it.
 *
 * @param effectivePriceTicks the price the notional and band checks use. For a LIMIT order
 *                            this is the limit price; for a MARKET order it is the current
 *                            contra-side quote widened by a slippage factor, because a
 *                            market order has no price of its own and risk still has to
 *                            bound the money at stake.
 */
public record RiskContext(
        OrderEntity order,
        InstrumentView instrument,
        AccountEntity account,
        PositionExposureEntity exposure,
        long effectivePriceTicks
) {
}
