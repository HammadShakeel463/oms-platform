package com.oms.common.error;

/**
 * Thrown when a pre-trade risk check refuses an order. The {@code check} name is carried
 * separately from the message so metrics can be tagged by which limit fired.
 */
public class RiskRejectedException extends OmsException {

    private final String check;

    public RiskRejectedException(String check, String message) {
        super(ErrorCode.RISK_LIMIT_BREACHED, message);
        this.check = check;
    }

    public String check() {
        return check;
    }
}
