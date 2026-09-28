package com.oms.common.error;

/**
 * Base of every deliberate, expected failure in the platform.
 *
 * <p>All of these extend {@code RuntimeException}, i.e. they are unchecked. That is the
 * modern Java convention and it is worth being able to defend: checked exceptions do not
 * compose with lambdas or {@code CompletableFuture}, and Spring maps runtime exceptions to
 * responses centrally in a {@code @RestControllerAdvice}, so a service method that cannot
 * do its job just throws and never pollutes its signature. The C++ instinct to think about
 * {@code noexcept} and error codes has no direct analogue here; the lever you actually
 * have is where you catch, not what you declare.
 *
 * <p>Carrying an {@link ErrorCode} on the exception is what lets one exception handler
 * produce the right HTTP status and the right machine-readable code without a chain of
 * {@code instanceof} checks.
 */
public abstract class OmsException extends RuntimeException {

    private final ErrorCode errorCode;

    protected OmsException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected OmsException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
