package com.oms.gateway.config;

import com.oms.web.security.OmsClaims;
import com.oms.web.security.OmsRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;

/**
 * The rate-limit key.
 *
 * <p>Only the key resolver is tested, not the token bucket. The bucket is Spring Cloud Gateway's
 * Redis Lua script; re-testing it would be testing the framework. <b>What counts as one client is
 * the decision this project made</b>, and it is the part worth pinning: a resolver that fell back to
 * IP for authenticated traffic would silently put a whole office behind one bucket, and a resolver
 * that read an account from a header would let a caller reset its own limit at will.
 */
class RateLimitConfigTest {

    private KeyResolver accountKeyResolver;
    private KeyResolver remoteAddressKeyResolver;

    @BeforeEach
    void setUp() {
        RateLimitConfig config = new RateLimitConfig();
        accountKeyResolver = config.accountKeyResolver();
        remoteAddressKeyResolver = config.remoteAddressKeyResolver();
    }

    private static MockServerWebExchange exchangeFrom(String ip) {
        return MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/v1/orders")
                .remoteAddress(new InetSocketAddress(ip, 54321)));
    }

    private static Jwt jwtWith(String accountId) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("hammad")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .claim(OmsClaims.ROLES, List.of(OmsRoles.TRADER));
        if (accountId != null) {
            builder.claim(OmsClaims.ACCOUNT_ID, accountId);
        }
        return builder.build();
    }

    private static JwtAuthenticationToken authentication(Jwt jwt) {
        return new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority(OmsRoles.ROLE_TRADER)), "hammad");
    }

    @Test
    @DisplayName("an authenticated caller is keyed by account, not by address")
    void authenticatedIsKeyedByAccount() {
        Mono<String> key = accountKeyResolver.resolve(exchangeFrom("10.1.2.3"))
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                        Mono.just(new SecurityContextImpl(authentication(jwtWith("ACC-TRADER-1"))))));

        StepVerifier.create(key)
                .expectNext("account:ACC-TRADER-1")
                .verifyComplete();
    }

    @Test
    @DisplayName("two callers from one address get separate buckets")
    void separateAccountsGetSeparateBuckets() {
        Mono<String> first = accountKeyResolver.resolve(exchangeFrom("10.1.2.3"))
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                        Mono.just(new SecurityContextImpl(authentication(jwtWith("ACC-A"))))));
        Mono<String> second = accountKeyResolver.resolve(exchangeFrom("10.1.2.3"))
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                        Mono.just(new SecurityContextImpl(authentication(jwtWith("ACC-B"))))));

        // The whole reason for not keying by IP: everyone behind one corporate NAT would
        // otherwise share a limit.
        StepVerifier.create(first).expectNext("account:ACC-A").verifyComplete();
        StepVerifier.create(second).expectNext("account:ACC-B").verifyComplete();
    }

    @Test
    @DisplayName("a token with no account claim falls back to the subject, not to nothing")
    void noAccountClaimFallsBackToSubject() {
        Mono<String> key = accountKeyResolver.resolve(exchangeFrom("10.1.2.3"))
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                        Mono.just(new SecurityContextImpl(authentication(jwtWith(null))))));

        StepVerifier.create(key)
                .expectNext("subject:hammad")
                .verifyComplete();
    }

    @Test
    @DisplayName("an unauthenticated request falls back to the remote address")
    void unauthenticatedFallsBackToAddress() {
        StepVerifier.create(accountKeyResolver.resolve(exchangeFrom("203.0.113.9")))
                .expectNext("ip:203.0.113.9")
                .verifyComplete();
    }

    @Test
    @DisplayName("a non-JWT authentication also falls back to the address")
    void nonJwtAuthenticationFallsBack() {
        Mono<String> key = accountKeyResolver.resolve(exchangeFrom("203.0.113.9"))
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                        Mono.just(new SecurityContextImpl(
                                new TestingAuthenticationToken("someone", "creds")))));

        StepVerifier.create(key)
                .expectNext("ip:203.0.113.9")
                .verifyComplete();
    }

    @Test
    @DisplayName("the address resolver keys the token endpoint, where there is no principal yet")
    void addressResolverForTheTokenEndpoint() {
        StepVerifier.create(remoteAddressKeyResolver.resolve(exchangeFrom("198.51.100.7")))
                .expectNext("ip:198.51.100.7")
                .verifyComplete();
    }

    @Test
    @DisplayName("a request with no remote address still yields a stable key")
    void missingAddressIsHandled() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/auth/token"));

        StepVerifier.create(remoteAddressKeyResolver.resolve(exchange))
                .expectNext("ip:unknown")
                .verifyComplete();
    }
}
