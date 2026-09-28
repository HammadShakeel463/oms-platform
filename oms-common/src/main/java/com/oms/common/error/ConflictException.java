package com.oms.common.error;

public class ConflictException extends OmsException {

    public ConflictException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public static ConflictException duplicateClientOrderId(String accountId, String clientOrderId) {
        return new ConflictException(ErrorCode.DUPLICATE_CLIENT_ORDER_ID,
                "clientOrderId " + clientOrderId + " already used by account " + accountId);
    }
}
