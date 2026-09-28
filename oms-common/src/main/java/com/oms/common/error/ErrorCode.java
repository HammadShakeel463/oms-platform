package com.oms.common.error;

/**
 * Machine-readable error taxonomy. Clients branch on {@code name()}, humans read
 * {@code message}, and the HTTP status is an attribute of the code rather than a
 * decision made ad hoc at every throw site.
 *
 * <p>The status is carried as an {@code int} so this contract module stays free of any
 * Spring dependency - oms-common must be usable by a plain client too.
 */
public enum ErrorCode {

    VALIDATION_FAILED(400, "Request failed validation"),
    MALFORMED_REQUEST(400, "Request body could not be parsed"),
    UNAUTHENTICATED(401, "Authentication required"),
    FORBIDDEN(403, "Caller is not permitted to perform this action"),
    ORDER_NOT_FOUND(404, "Order not found"),
    INSTRUMENT_NOT_FOUND(404, "Instrument not found"),
    ACCOUNT_NOT_FOUND(404, "Account not found"),
    DUPLICATE_CLIENT_ORDER_ID(409, "clientOrderId already used by this account"),
    ILLEGAL_STATE_TRANSITION(409, "Order is not in a state that allows this operation"),
    CONCURRENT_MODIFICATION(409, "Order was modified concurrently, retry"),
    RISK_LIMIT_BREACHED(422, "Pre-trade risk check failed"),
    MARKET_CLOSED(422, "Market is not open for trading"),
    RATE_LIMITED(429, "Too many requests"),
    UPSTREAM_UNAVAILABLE(503, "A downstream dependency is unavailable"),
    INTERNAL_ERROR(500, "Unexpected internal error");

    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
