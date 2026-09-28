package com.oms.common.error;

import com.oms.common.domain.OrderStatus;

/** An order was asked to move to a state {@link OrderStatus#canTransitionTo} forbids. */
public class IllegalStateTransitionException extends OmsException {

    private final OrderStatus from;
    private final OrderStatus to;

    public IllegalStateTransitionException(OrderStatus from, OrderStatus to) {
        super(ErrorCode.ILLEGAL_STATE_TRANSITION,
                "Cannot transition order from " + from + " to " + to
                        + "; allowed: " + from.allowedTargets());
        this.from = from;
        this.to = to;
    }

    public OrderStatus from() {
        return from;
    }

    public OrderStatus to() {
        return to;
    }
}
