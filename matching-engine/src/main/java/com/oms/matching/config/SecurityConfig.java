package com.oms.matching.config;

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
 * matching-engine as an OAuth2 resource server.
 *
 * <p>The engine has the smallest HTTP surface in the platform and the most sensitive one. Full
 * depth of book reveals every resting order, its size and its price - which for a market maker is
 * the position they are trying not to broadcast. It is restricted to ADMIN, not because operators
 * are more trusted in general, but because there is no legitimate client-facing use for it: a
 * client that wants prices subscribes to market data, which publishes aggregated top-of-book.
 *
 * <p>The engine's real input arrives over Kafka, not HTTP, and is not authenticated by this filter
 * chain at all. Broker-level authentication (SASL) and topic ACLs are the control there, and they
 * belong in Phase 6 with the rest of the deployment configuration - noted here so the gap is
 * recorded rather than implied to be covered.
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
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/**").hasRole(OmsRoles.ADMIN)

                        .requestMatchers("/api/v1/books/**").hasRole(OmsRoles.ADMIN)

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
