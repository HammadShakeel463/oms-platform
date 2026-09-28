package com.oms.order.api;

import com.oms.common.error.ApiError;
import com.oms.common.error.ErrorCode;
import com.oms.common.error.OmsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
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
 * Turns every exception into the one error contract.
 *
 * <p>{@code @RestControllerAdvice} registers these handlers across every controller in the
 * application. The alternative - try/catch in each controller method - produces the failure
 * mode this class exists to prevent: the same condition returning a different status and a
 * different body depending on which endpoint hit it.
 *
 * <p>Handler selection is by most-specific exception type, so
 * {@code handleValidation(MethodArgumentNotValidException)} wins over the {@code Exception}
 * catch-all without any ordering annotation.
 *
 * <p>A C++ reader will recognise the shape: this is the single top-level catch that turns
 * an exception into a return code for the caller. The difference is that it is installed
 * declaratively and applies to every request handler in the process, including ones written
 * later by someone who never reads this class.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Every deliberate failure in the platform. The status and the code both come from the
     * exception, so a new error type needs no change here.
     */
    @ExceptionHandler(OmsException.class)
    public ResponseEntity<ApiError> handleOms(OmsException e, HttpServletRequest request) {
        ErrorCode code = e.errorCode();
        // 5xx is a fault in this service and gets a stack trace; 4xx is the caller being
        // told something and gets one line. Logging client errors at ERROR with stack
        // traces is how an error log stops being useful.
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

    /** Bean Validation failures on {@code @RequestParam}/{@code @PathVariable}. */
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
     * <p>The exception message is deliberately not echoed: Jackson's message quotes the
     * offending input and the target class name, which hands an attacker a map of internal
     * types. The caller gets a stable, unhelpful-on-purpose message; the detail goes to the
     * log with the trace id.
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
     * Two orders for the same {@code (accountId, clientOrderId)} raced past the pre-check
     * and one lost at the UNIQUE constraint. That is the constraint doing its job, so it is
     * reported as a 409 rather than a 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException e,
                                                    HttpServletRequest request) {
        log.warn("Integrity violation on {} {}: {}", request.getMethod(),
                request.getRequestURI(), e.getMostSpecificCause().getMessage());
        return respond(ErrorCode.DUPLICATE_CLIENT_ORDER_ID,
                "The request conflicts with existing data", request);
    }

    /** The optimistic lock fired: someone else changed the order first. Retryable. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException e,
                                                         HttpServletRequest request) {
        log.warn("Optimistic lock conflict on {} {}", request.getMethod(), request.getRequestURI());
        return respond(ErrorCode.CONCURRENT_MODIFICATION,
                "The order was modified concurrently; reload it and retry", request);
    }

    /**
     * The backstop. Anything reaching here is a bug in this service.
     *
     * <p>The response carries no exception detail at all - only the trace id. An internal
     * error message tells a caller about class names, SQL fragments and file paths, and the
     * legitimate caller can do nothing with any of it. The trace id is the handle: it is in
     * the response, in the log line, and on the span.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), e);
        return respond(ErrorCode.INTERNAL_ERROR,
                "An internal error occurred. Quote the traceId when reporting it.", request);
    }

    private ResponseEntity<ApiError> respond(ErrorCode code, String message,
                                             HttpServletRequest request) {
        return ResponseEntity.status(code.httpStatus())
                .body(ApiError.of(code, message, request.getRequestURI(), traceId()));
    }

    private static String traceId() {
        return MDC.get("traceId");
    }
}
