package com.oms.web;

import com.oms.common.error.ApiError;
import com.oms.common.error.ErrorCode;
import com.oms.common.error.OmsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Objects;

/**
 * Turns every exception into the one {@link ApiError} contract, in every service.
 *
 * <p>Lives in {@code oms-web} rather than in each service because the alternative is four
 * copies that drift: the same failure returning 422 from one service and 500 from another is
 * exactly the defect a shared error contract exists to prevent. It cannot live in
 * {@code oms-common}, which is deliberately free of Spring (ADR 0001) - so the contract
 * (records, enums) is in one module and the rendering of it is in another.
 *
 * <p><b>Ordering.</b> This advice is {@code LOWEST_PRECEDENCE} because it owns the
 * {@code Exception} catch-all. Spring resolves a handler by walking advices in order and
 * taking the best match <em>within the first advice that has one</em>, so a catch-all in a
 * high-precedence advice would swallow exceptions that a more specific handler elsewhere -
 * {@link PersistenceExceptionHandler} - was written for. The catch-all must be consulted last.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Every deliberate failure in the platform. The status and the machine-readable code both
     * come from the exception, so a new error type needs no change here.
     */
    @ExceptionHandler(OmsException.class)
    public ResponseEntity<ApiError> handleOms(OmsException e, HttpServletRequest request) {
        ErrorCode code = e.errorCode();
        // 5xx is a fault in this service and gets a stack trace; 4xx is the caller being told
        // something and gets one line. Logging client errors at ERROR with stack traces is how
        // an error log stops being useful.
        if (code.httpStatus() >= 500) {
            log.error("{} handling {} {}", code, request.getMethod(), request.getRequestURI(), e);
        } else {
            log.info("{} handling {} {}: {}", code, request.getMethod(),
                    request.getRequestURI(), e.getMessage());
        }
        return respond(code, e.getMessage(), request);
    }

    /** Bean Validation failures on a {@code @Valid @RequestBody}. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e,
                                                     HttpServletRequest request) {
        List<ApiError.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(
                        fe.getField(),
                        Objects.requireNonNullElse(fe.getDefaultMessage(), "is invalid"),
                        String.valueOf(fe.getRejectedValue())))
                .toList();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.validation("Request validation failed",
                        request.getRequestURI(), traceId(), fieldErrors));
    }

    /** Bean Validation failures on {@code @RequestParam} / {@code @PathVariable}. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException e,
                                                              HttpServletRequest request) {
        List<ApiError.FieldError> fieldErrors = e.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldError(
                        String.valueOf(v.getPropertyPath()),
                        v.getMessage(),
                        String.valueOf(v.getInvalidValue())))
                .toList();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.validation("Request validation failed",
                        request.getRequestURI(), traceId(), fieldErrors));
    }

    /**
     * Unparseable JSON, or a value that cannot be bound - an unknown enum constant, a
     * malformed UUID, a string where a number belongs.
     *
     * <p>The exception message is deliberately not echoed: Jackson quotes the offending input
     * and the target class name, which hands a caller a map of internal types. The detail goes
     * to the log with the trace id.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e,
                                                     HttpServletRequest request) {
        log.info("Malformed request body on {} {}: {}", request.getMethod(),
                request.getRequestURI(), e.getMostSpecificCause().getMessage());
        return respond(ErrorCode.MALFORMED_REQUEST,
                "Request body could not be parsed; check field types and enum values", request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException e,
                                                        HttpServletRequest request) {
        return respond(ErrorCode.VALIDATION_FAILED,
                "Missing required header: " + e.getHeaderName(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                       HttpServletRequest request) {
        return respond(ErrorCode.VALIDATION_FAILED,
                "Parameter '" + e.getName() + "' has an invalid value", request);
    }

    /**
     * The backstop. Anything reaching here is a bug in the service.
     *
     * <p>The response carries no exception detail at all - only the trace id. An internal
     * error message tells a caller about class names, SQL fragments and file paths, and a
     * legitimate caller can do nothing with any of it. The trace id is the handle: it is in
     * the response, in the log line, and on the span.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), e);
        return respond(ErrorCode.INTERNAL_ERROR,
                "An internal error occurred. Quote the traceId when reporting it.", request);
    }

    static ResponseEntity<ApiError> respond(ErrorCode code, String message,
                                            HttpServletRequest request) {
        return ResponseEntity.status(code.httpStatus())
                .body(ApiError.of(code, message, request.getRequestURI(), traceId()));
    }

    static String traceId() {
        return MDC.get(TraceIdFilter.MDC_KEY);
    }
}
