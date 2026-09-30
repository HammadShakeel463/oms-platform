package com.oms.marketdata.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.web.security.OmsJwtAuthenticationConverter;
import com.oms.web.security.OmsRoles;
import com.oms.web.security.ResourceServerSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * market-data-service as an OAuth2 resource server.
 *
 * <p>The token is validated here even though the gateway already validated it. The security
 * boundary has to be the service, not the network topology: anything able to reach this pod
 * directly must not be able to claim an account by setting a header.
 *
 * <p>Market data is the least sensitive surface in the platform - a quote is not account data, and
 * every role needs prices to do its job - so the rule is simply "authenticated". It is still not
 * public: an unauthenticated feed of a venue's prices is a product somebody sells.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri) {

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
                // No sessions, so there is no cookie for a cross-site request to ride on:
                // disabling CSRF is a consequence of stateless bearer auth, not a shortcut.
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(requests -> requests
                        // Probes must work without a token or a rollout never becomes ready.
                        // Scoped to the probe endpoints - /actuator/prometheus is not public.
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/**").hasRole(OmsRoles.ADMIN)

                        .requestMatchers("/api/v1/instruments/**", "/api/v1/quotes/**")
                                .authenticated()

                        .anyRequest().authenticated())

                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(converter)))

                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(
                                ResourceServerSupport.unauthorizedEntryPoint(objectMapper))
                        .accessDeniedHandler(
                                ResourceServerSupport.forbiddenHandler(objectMapper)))
                .build();
    }
}
