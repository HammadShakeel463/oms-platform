package com.oms.web;

import com.oms.common.error.ApiError;
import com.oms.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Database failures that have a meaningful HTTP answer.
 *
 * <p>A separate advice from {@link ApiExceptionHandler} for a concrete reason: matching-engine
 * has no database and therefore no {@code spring-tx} on its runtime classpath. If these
 * handlers lived in the shared advice, loading that class in the engine would fail with
 * {@code NoClassDefFoundError} on {@code DataAccessException} - a service that will not start
 * because of a handler for a dependency it does not have.
 *
 * <p>Registered only when Spring's DAO support is present. {@code @ConditionalOnClass} on the
 * auto-configuration is how a Boot library offers a feature that depends on an optional
 * dependency, and this is the honest use of it: not a nicety, but the difference between the
 * module being reusable and being a landmine.
 *
 * <p>Higher precedence than {@link ApiExceptionHandler}, whose {@code Exception} catch-all
 * would otherwise claim these first.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PersistenceExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PersistenceExceptionHandler.class);

    /**
     * A unique or check constraint refused the write.
     *
     * <p>Reported as a 409 rather than a 500: the constraint doing its job is not a server
     * fault. Two concurrent retries of the same order both pass an application-level
     * existence check and exactly one reaches the constraint - which is precisely why the
     * constraint, not the check, is the correctness guarantee.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException e,
                                                    HttpServletRequest request) {
        log.warn("Integrity violation on {} {}: {}", request.getMethod(),
                request.getRequestURI(), e.getMostSpecificCause().getMessage());
        return ApiExceptionHandler.respond(ErrorCode.DUPLICATE_CLIENT_ORDER_ID,
                "The request conflicts with existing data", request);
    }

    /** The optimistic lock fired: someone else changed the row first. Retryable. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException e,
                                                         HttpServletRequest request) {
        log.warn("Optimistic lock conflict on {} {}", request.getMethod(), request.getRequestURI());
        return ApiExceptionHandler.respond(ErrorCode.CONCURRENT_MODIFICATION,
                "The record was modified concurrently; reload it and retry", request);
    }
}
