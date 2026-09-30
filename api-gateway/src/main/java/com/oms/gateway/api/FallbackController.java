package com.oms.gateway.api;

import com.oms.common.error.ApiError;
import com.oms.common.error.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * What a caller gets when a downstream service is unreachable or the circuit is open.
 *
 * <p>Two things this does that a bare timeout does not.
 *
 * <p><b>It answers in the platform error contract.</b> Gateway default behaviour for an open circuit
 * is a 503 with an empty body, which means the one component every client talks to is also the one
 * component that answers in a different shape when things go wrong - exactly when a client most
 * needs a machine-readable code.
 *
 * <p><b>It fails fast instead of holding the caller.</b> Without a circuit breaker, every request to
 * a dead service waits out the connect timeout, and the gateway accumulates pending requests until
 * it is as unhealthy as the thing it is calling. That is the cascade a circuit breaker exists to
 * stop: once the failure rate crosses the threshold, calls are refused immediately and the
 * downstream gets a chance to recover instead of being retried to death.
 *
 * <p>UPSTREAM_UNAVAILABLE rather than INTERNAL_ERROR, because the distinction matters to the caller:
 * a 503 with this code is worth retrying with backoff, and a 500 is not.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @RequestMapping("/{service}")
    public Mono<ResponseEntity<ApiError>> fallback(
            @org.springframework.web.bind.annotation.PathVariable String service,
            ServerWebExchange exchange) {

        String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");

        ApiError error = ApiError.of(ErrorCode.UPSTREAM_UNAVAILABLE,
                service + " is not reachable. The request was not processed; retry with backoff.",
                exchange.getRequest().getPath().value(),
                traceId != null ? traceId : exchange.getRequest().getId());

        return Mono.just(ResponseEntity
                .status(ErrorCode.UPSTREAM_UNAVAILABLE.httpStatus())
                .body(error));
    }
}
