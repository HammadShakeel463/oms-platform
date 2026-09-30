package com.oms.order.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.web.security.OmsJwtAuthenticationConverter;
import com.oms.web.security.OmsRoles;
import com.oms.web.security.ResourceServerSupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * order-service as an OAuth2 resource server.
 *
 * <h2>Why this exists when the gateway already validated the token</h2>
 *
 * <p>Because <b>the security boundary should be the service, not the network</b>. If this service
 * trusted a gateway-injected header, then anything able to reach the pod directly - a debug port, a
 * misapplied NetworkPolicy, a compromised sidecar, a developer port-forward - could claim any
 * account with any role. Re-validating costs a cached JWKS lookup and a signature check, and it
 * means a network mistake is a network mistake rather than a total authorisation bypass.
 *
 * <p>This is also what makes the {@code @AccountId} resolver safe: the account comes from a claim in
 * a signature-verified token, not from a header the caller supplied. Phases 2 to 4 read
 * {@code X-Account-Id} because nothing was authenticated yet; the swap was a one-line change per
 * endpoint because the header was read in exactly one place.
 *
 * <h2>Stateless, and what that implies</h2>
 *
 * <p>{@code SessionCreationPolicy.STATELESS} - no {@code JSESSIONID}, no session storage, nothing to
 * replicate between instances. That is what lets any instance serve any request, and it is why CSRF
 * protection is disabled: CSRF exploits credentials the browser attaches automatically, and a bearer
 * token is not one. Disabling CSRF on a session-authenticated application would be a serious bug;
 * here it is a consequence of not having sessions.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Verifies tokens using the public keys published by the gateway.
     *
     * <p>{@code jwk-set-uri} rather than a configured public key: the service fetches the key set on
     * first use, caches it, and re-fetches when it encounters an unknown key id. Rotation then means
     * publishing a new key at the gateway and waiting for caches to catch up, rather than
     * redeploying four services with new configuration.
     */
    @Bean
    public JwtDecoder jwtDecoder(
            @org.springframework.beans.factory.annotation.Value(
                    "${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri) {

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(ResourceServerSupport.tokenValidator());
        return decoder;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtDecoder jwtDecoder,
                                           OmsJwtAuthenticationConverter converter,
                                           ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(requests -> requests
                        // Kubernetes probes must work without a token, or a rollout never becomes
                        // ready. Scoped to the probe endpoints only - /actuator/prometheus carries
                        // order rates and account activity.
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/**").hasRole(OmsRoles.ADMIN)

                        // Defence in depth: the gateway enforces the same rules. Duplicated
                        // deliberately, because a request that bypasses the gateway must not be
                        // able to write an order.
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders/**")
                                .hasRole(OmsRoles.TRADER)
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/orders/**")
                                .hasRole(OmsRoles.TRADER)
                        .requestMatchers(HttpMethod.GET, "/api/v1/orders/**")
                                .hasAnyRole(OmsRoles.TRADER, OmsRoles.RISK, OmsRoles.ADMIN)

                        .anyRequest().authenticated())

                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                // Without this the roles claim is ignored and every hasRole check
                                // silently denies.
                                .jwtAuthenticationConverter(converter)))

                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(
                                ResourceServerSupport.unauthorizedEntryPoint(objectMapper))
                        .accessDeniedHandler(
                                ResourceServerSupport.forbiddenHandler(objectMapper)))
                .build();
    }
}
