package com.oms.gateway.config;

import com.oms.web.security.OmsClaims;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

/**
 * Rate limiting keys.
 *
 * <p>Spring Cloud Gateway's {@code RequestRateLimiter} filter is a token bucket held in Redis, and
 * the only decision it leaves to the application is <b>what to count per</b>. That decision is the
 * whole design:
 *
 * <table>
 *   <tr><th>Key</th><th>Problem</th></tr>
 *   <tr><td>Global</td><td>One busy client throttles everyone. A shared limit is a shared outage.</td></tr>
 *   <tr><td>Client IP</td><td>Every user behind one corporate NAT shares a bucket. Meanwhile an
 *       attacker with a handful of addresses gets a bucket each.</td></tr>
 *   <tr><td><b>Authenticated account</b></td><td>The unit that actually corresponds to a user of the
 *       system, and it cannot be changed by the caller because it comes from a signed claim.</td></tr>
 * </table>
 *
 * <p>Redis rather than an in-memory counter because the limit has to hold across gateway replicas:
 * three instances with local counters give a client three times the limit, and the limit then
 * changes every time the deployment is scaled.
 */
@Configuration
public class RateLimitConfig {

    /**
     * Keys authenticated traffic by account, falling back to remote address.
     *
     * <p>{@code @Primary} because Gateway injects a single {@code KeyResolver} by type when a route
     * does not name one.
     */
    @Bean
    @Primary
    public KeyResolver accountKeyResolver() {
        return exchange -> ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .filter(authentication -> authentication instanceof JwtAuthenticationToken)
                .cast(JwtAuthenticationToken.class)
                .map(authentication -> {
                    String accountId = authentication.getToken()
                            .getClaimAsString(OmsClaims.ACCOUNT_ID);
                    return accountId != null ? "account:" + accountId
                            : "subject:" + authentication.getName();
                })
                .switchIfEmpty(Mono.fromSupplier(() -> remoteAddressKey(exchange)));
    }

    /**
     * Keys unauthenticated traffic - the token endpoint - by remote address.
     *
     * <p>There is no principal yet, so address is the only thing available. It is a weak key and
     * that is acknowledged rather than glossed over: an attacker with many addresses gets many
     * buckets. It still raises the cost of credential stuffing from one machine, and it is paired
     * with a far tighter limit than the authenticated routes, because each attempt costs a
     * deliberately slow BCrypt comparison.
     *
     * <p>Behind a load balancer this depends on {@code X-Forwarded-For} being set by the proxy and
     * <b>not</b> trusted blindly - a client-supplied value would let an attacker rotate the key at
     * will. Spring Cloud Gateway is configured with a trusted-proxy hop count for that reason.
     */
    @Bean("remoteAddressKeyResolver")
    public KeyResolver remoteAddressKeyResolver() {
        return exchange -> Mono.just(remoteAddressKey(exchange));
    }

    private static String remoteAddressKey(org.springframework.web.server.ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        return "ip:" + (remote != null && remote.getAddress() != null
                ? remote.getAddress().getHostAddress()
                : "unknown");
    }
}
