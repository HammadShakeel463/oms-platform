package com.oms.gateway.config;

import com.oms.common.error.ApiError;
import com.oms.common.error.ErrorCode;
import com.oms.gateway.auth.SigningKeys;
import com.oms.web.security.OmsClaims;
import com.oms.web.security.OmsJwtAuthenticationConverter;
import com.oms.web.security.OmsRoles;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Who may call what.
 *
 * <h2>Defence in depth: the gateway validates, and so does every service</h2>
 *
 * <p>The gateway verifies the token and enforces coarse-grained route rules. It then forwards the
 * {@code Authorization} header unchanged, and <b>each downstream service validates the same token
 * again</b> and derives the account from the claim rather than from a header.
 *
 * <p>The tempting alternative is for the gateway to validate once and inject a trusted
 * {@code X-Account-Id} header, with services trusting it. That is one network misconfiguration away
 * from catastrophe: anything that can reach a service pod directly - a debug port, a misapplied
 * NetworkPolicy, a compromised sidecar - can then set that header to any account it likes and the
 * service will believe it. Re-validating costs a cached JWKS lookup and a signature check, and it
 * means <b>the security boundary is the service, not the network topology</b>.
 *
 * <p>Phases 2 to 4 read {@code X-Account-Id} because nothing was authenticated yet. Phase 5 replaces
 * it with {@code @AccountId}, which reads a signed claim. Each service needed a one-line change
 * because the header was read in exactly one place - which was the point of putting it there.
 *
 * <h2>Validation is more than a signature check</h2>
 *
 * <p>A correct signature only proves the token was minted by the holder of the private key. The
 * decoder below also enforces:
 * <ul>
 *   <li><b>expiry and not-before</b>, with a small clock skew allowance - without skew, two hosts a
 *       second apart reject each other's freshly minted tokens;</li>
 *   <li><b>issuer</b> - a token from a different issuer is not ours;</li>
 *   <li><b>audience</b> - a token this issuer minted for a <em>different system</em> must not work
 *       here. This is the check people most often leave out, and it is the one that stops a token
 *       intended for an unrelated service being replayed against this one.</li>
 * </ul>
 */
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    /**
     * BCrypt, with the cost factor left at the Spring default (10).
     *
     * <p>BCrypt rather than a plain hash because it is deliberately slow and salted per password:
     * a leaked table cannot be attacked with a precomputed rainbow table, and each guess costs real
     * CPU. That slowness is the feature, which is also why the token endpoint is the most
     * rate-limited route on the gateway.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * The gateway verifies with the public half of its own key, in memory - no HTTP round trip to
     * its own JWKS endpoint.
     */
    @Bean
    public ReactiveJwtDecoder jwtDecoder(SigningKeys signingKeys) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withPublicKey(signingKeys.publicKey())
                .build();
        decoder.setJwtValidator(tokenValidator());
        return decoder;
    }

    /** Expiry with skew, issuer, and - the one usually forgotten - audience. */
    static OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> tokenValidator() {
        return new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ofSeconds(30)),
                JwtValidators.createDefaultWithIssuer(OmsClaims.ISSUER),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audience -> audience != null && audience.contains(OmsClaims.AUDIENCE)));
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http, ReactiveJwtDecoder jwtDecoder, ObjectMapper objectMapper) {

        return http
                // No cookies, no sessions, no browser-form login: every request carries a bearer
                // token. CSRF protects cookie-authenticated requests, and a token the browser does
                // not attach automatically is not vulnerable to it - so disabling CSRF here is
                // correct rather than a shortcut. It would NOT be correct if sessions were used.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)

                .authorizeExchange(exchanges -> exchanges
                        // --- public ---------------------------------------------------------
                        .pathMatchers(HttpMethod.POST, "/auth/token").permitAll()
                        // A public key is public, and requiring a token to fetch the key needed to
                        // verify tokens has no solution.
                        .pathMatchers(HttpMethod.GET, "/oauth2/jwks").permitAll()
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers("/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs/**", "/webjars/**").permitAll()
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()

                        // --- operational ----------------------------------------------------
                        // Metrics carry order rates, book depth and account activity. Public
                        // /actuator/prometheus is a slow information leak that nobody notices.
                        .pathMatchers("/actuator/**").hasRole(OmsRoles.ADMIN)

                        // --- trading --------------------------------------------------------
                        // Writing an order requires TRADER. RISK deliberately cannot trade: a risk
                        // officer who can place orders is not a control, and separation of duties
                        // is the entire reason the role exists.
                        .pathMatchers(HttpMethod.POST, "/api/v1/orders/**").hasRole(OmsRoles.TRADER)
                        .pathMatchers(HttpMethod.DELETE, "/api/v1/orders/**").hasRole(OmsRoles.TRADER)
                        .pathMatchers(HttpMethod.GET, "/api/v1/orders/**")
                                .hasAnyRole(OmsRoles.TRADER, OmsRoles.RISK, OmsRoles.ADMIN)

                        // --- positions and risk ---------------------------------------------
                        .pathMatchers("/api/v1/positions/**", "/api/v1/pnl")
                                .hasAnyRole(OmsRoles.TRADER, OmsRoles.RISK, OmsRoles.ADMIN)

                        // --- market data ----------------------------------------------------
                        // Readable by any authenticated caller: a quote is not account data, and
                        // every role needs prices to do its job.
                        .pathMatchers("/api/v1/instruments/**", "/api/v1/quotes/**").authenticated()

                        // --- engine internals -----------------------------------------------
                        // Full book depth reveals every resting order. Operators only.
                        .pathMatchers("/api/v1/books/**").hasRole(OmsRoles.ADMIN)

                        .anyExchange().authenticated())

                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtDecoder(jwtDecoder)
                                // Without this the roles claim is ignored entirely and every
                                // hasRole check silently denies. The single most common resource
                                // server misconfiguration.
                                .jwtAuthenticationConverter(reactiveConverter())))

                // Both handlers return the platform ApiError contract. Spring's defaults return an
                // empty body with a WWW-Authenticate header, which would make the gateway the one
                // component in the platform that answers in a different shape.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthorizedEntryPoint(objectMapper))
                        .accessDeniedHandler(forbiddenHandler(objectMapper)))
                .build();
    }

    private static org.springframework.core.convert.converter.Converter<
            org.springframework.security.oauth2.jwt.Jwt, Mono<AbstractAuthenticationToken>>
    reactiveConverter() {
        return new ReactiveJwtAuthenticationConverterAdapter(new OmsJwtAuthenticationConverter());
    }

    /** 401: no token, or a token that does not verify. */
    private static ServerAuthenticationEntryPoint unauthorizedEntryPoint(ObjectMapper mapper) {
        return (exchange, exception) -> write(exchange, mapper, HttpStatus.UNAUTHORIZED,
                ErrorCode.UNAUTHENTICATED,
                "A valid bearer token is required. Obtain one from POST /auth/token.");
    }

    /**
     * 403: a valid token, but the wrong role.
     *
     * <p>401 and 403 are distinct on purpose: 401 means "authenticate", 403 means "authenticating
     * differently will not help". Collapsing them makes a client retry a login that cannot succeed.
     */
    private static ServerAccessDeniedHandler forbiddenHandler(ObjectMapper mapper) {
        return (exchange, exception) -> write(exchange, mapper, HttpStatus.FORBIDDEN,
                ErrorCode.FORBIDDEN,
                "Your roles do not permit this operation.");
    }

    private static Mono<Void> write(org.springframework.web.server.ServerWebExchange exchange,
                                    ObjectMapper mapper, HttpStatus status,
                                    ErrorCode code, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders()
                .setContentType(MediaType.APPLICATION_JSON);

        String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        ApiError error = ApiError.of(code, message,
                exchange.getRequest().getPath().value(),
                traceId != null ? traceId : exchange.getRequest().getId());

        byte[] body;
        try {
            body = mapper.writeValueAsBytes(error);
        } catch (Exception e) {
            body = ("{\"code\":\"" + code.name() + "\"}").getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        exchange.getResponse().getHeaders().setContentLength(body.length);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
