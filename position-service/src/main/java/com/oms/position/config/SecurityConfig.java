package com.oms.position.config;

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
 * position-service as an OAuth2 resource server.
 *
 * <p>The token is validated here even though the gateway already validated it. The security
 * boundary has to be the service, not the network topology: anything able to reach this pod
 * directly must not be able to claim an account by setting a header.
 *
 * <p><b>Note what the role rule does and does not decide.</b> The role decides whether the endpoint
 * may be called at all; <em>which rows come back</em> is decided by the account claim, which the
 * controller takes from the verified token and passes to the query. Authorisation by role alone -
 * "TRADER may read positions" - without scoping the query to the caller's account would let any
 * trader read any other trader's book. That is the classic broken-object-level-authorisation bug,
 * and the defence is that every query here is parameterised by the account from the token rather
 * than by anything the caller supplied.
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

                        // RISK is read-only by design: a risk officer who can also trade is not a
                        // control. Separation of duties is the whole reason the role exists, and
                        // there is deliberately no write endpoint here for it to reach.
                        .requestMatchers("/api/v1/positions/**", "/api/v1/pnl")
                                .hasAnyRole(OmsRoles.TRADER, OmsRoles.RISK, OmsRoles.ADMIN)

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
