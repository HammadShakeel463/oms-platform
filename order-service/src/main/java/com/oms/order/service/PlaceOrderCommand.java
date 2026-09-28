package com.oms.order.service;

import com.oms.common.domain.OrderType;
import com.oms.common.domain.Side;
import com.oms.common.domain.TimeInForce;

import java.math.BigDecimal;

/**
 * What the service layer needs to place an order.
 *
 * <p>Separate from the web-layer request DTO on purpose. The DTO carries validation
 * annotations, Jackson naming and whatever shape the API happens to expose; the command is
 * the service's own vocabulary. Keeping them apart means an API change - a renamed field, a
 * new optional parameter, a second version of the endpoint - does not reach into the
 * service, and the service can be driven from a test or a future Kafka intake without
 * constructing an HTTP request object.
 */
public record PlaceOrderCommand(
        String accountId,
        String clientOrderId,
        String symbol,
        Side side,
        OrderType orderType,
        TimeInForce timeInForce,
        BigDecimal limitPrice,
        long quantity,
        String submittedBy
) {
}
