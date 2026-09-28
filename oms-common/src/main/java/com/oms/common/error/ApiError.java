package com.oms.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * The single error body every service returns, for every failure, at every status.
 * One shape means a client writes one error handler.
 *
 * @param traceId    the distributed-trace id, so a support ticket maps to a trace in one step
 * @param code       stable machine-readable code from {@link ErrorCode}
 * @param fieldErrors populated only for validation failures
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String traceId,
        List<FieldError> fieldErrors
) {

    public record FieldError(String field, String message, String rejectedValue) {
    }

    public static ApiError of(ErrorCode code, String message, String path, String traceId) {
        return new ApiError(Instant.now(), code.httpStatus(), code.name(),
                message != null ? message : code.defaultMessage(), path, traceId, List.of());
    }

    public static ApiError validation(String message, String path, String traceId,
                                      List<FieldError> fieldErrors) {
        return new ApiError(Instant.now(), ErrorCode.VALIDATION_FAILED.httpStatus(),
                ErrorCode.VALIDATION_FAILED.name(), message, path, traceId, fieldErrors);
    }
}
