package com.oms.web.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Replaces the JWKS-fetching decoder with one that trusts the in-test signing key.
 *
 * <p>Import it from an integration test:
 *
 * <pre>
 * &#64;SpringBootTest
 * &#64;Import(TestSecurityConfig.class)
 * class OrderFlowIT { ... }
 * </pre>
 *
 * <p>Only the key source changes. The filter chain, the roles converter, the
 * {@code @AccountId} resolver and the production token validator are all the real ones, so
 * these tests still fail if the security rules or the claim contract change.
 *
 * <p>Bean overriding is required because each service declares its own {@code jwtDecoder}
 * bean, and in production that bean must exist. {@code @Primary} plus
 * {@code spring.main.allow-bean-definition-overriding=true} in the integration-test profile
 * is how a test replaces it. That property is deliberately scoped to the test profile - enabled
 * globally it would let any accidental duplicate bean definition silently win, which is a
 * class of bug worth keeping loud in production.
 */
@TestConfiguration
public class TestSecurityConfig {

    @Bean
    @Primary
    public JwtDecoder jwtDecoder() {
        return TestJwt.decoder();
    }
}
