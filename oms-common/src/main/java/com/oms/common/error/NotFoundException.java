package com.oms.common.error;

public class NotFoundException extends OmsException {

    public NotFoundException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public static NotFoundException order(Object id) {
        return new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "No order with id " + id);
    }

    public static NotFoundException instrument(String symbol) {
        return new NotFoundException(ErrorCode.INSTRUMENT_NOT_FOUND, "Unknown symbol " + symbol);
    }

    public static NotFoundException account(String accountId) {
        return new NotFoundException(ErrorCode.ACCOUNT_NOT_FOUND, "Unknown account " + accountId);
    }
}
