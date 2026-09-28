package com.oms.order.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Puts a trace id into the logging context for the life of the request.
 *
 * <p>MDC is a ThreadLocal map that the logging framework reads when it formats a line, so
 * every log statement during this request carries the id without any call site passing it.
 * Phase 6 replaces this filter with Micrometer Tracing, which produces W3C-compatible ids
 * and propagates them across services and over Kafka headers; the MDC key stays the same,
 * so nothing that reads it has to change.
 *
 * <p>The ThreadLocal is why the {@code finally} block is not optional. Servlet containers
 * pool threads, so a value left behind is inherited by the next unrelated request and
 * quietly attributes its logs to the wrong trace. Same discipline as any thread-local
 * resource in C++: the cleanup is the contract.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        MDC.put(MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
