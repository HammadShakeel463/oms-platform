package com.oms.gateway.auth;

import com.oms.common.error.ApiError;
import com.oms.common.error.ErrorCode;
import com.oms.web.security.OmsClaims;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * Token issuance.
 *
 * <p>Shaped like an OAuth2 password grant without claiming to be one. The real OAuth2 password
 * grant is deprecated precisely because it requires the client to handle the user's password, and a
 * production deployment replaces this endpoint with a redirect to an identity provider (ADR 0007).
 * It exists so the platform is demonstrable end to end.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserStore userStore;
    private final TokenIssuer tokenIssuer;
    private final Counter successes;
    private final Counter failures;

    public AuthController(UserStore userStore, TokenIssuer tokenIssuer,
                          MeterRegistry meterRegistry) {
        this.userStore = userStore;
        this.tokenIssuer = tokenIssuer;
        this.successes = meterRegistry.counter("oms.auth.login", "outcome", "success");
        this.failures = meterRegistry.counter("oms.auth.login", "outcome", "failure");
    }

    public record TokenRequest(
            @NotBlank(message = "username is required") @Size(max = 64) String username,
            @NotBlank(message = "password is required") @Size(max = 256) String password
    ) {
    }

    /**
     * Exchanges credentials for an access token.
     *
     * <p><b>401 with a deliberately vague message.</b> "No such user" and "wrong password" get the
     * same response, because distinguishing them hands an attacker a username oracle - and the
     * legitimate caller can do nothing with the distinction anyway.
     *
     * <p>This endpoint is also the most heavily rate-limited route on the gateway: it is the one
     * place an attacker can guess at, and it is cheap for them and expensive for us (a BCrypt
     * comparison per attempt, by design).
     */
    @PostMapping("/token")
    public Mono<ResponseEntity<?>> token(@Valid @RequestBody TokenRequest request,
                                         ServerWebExchange exchange) {
        return Mono.fromCallable(() -> userStore.authenticate(request.username(), request.password()))
                .map(maybeUser -> maybeUser
                        .map(user -> {
                            successes.increment();
                            log.info("Issued a token for {} (account {})",
                                    user.username(), user.accountId());
                            return ResponseEntity.ok((Object) tokenIssuer.issue(user));
                        })
                        .orElseGet(() -> {
                            failures.increment();
                            return ResponseEntity
                                    .status(ErrorCode.UNAUTHENTICATED.httpStatus())
                                    .body(ApiError.of(ErrorCode.UNAUTHENTICATED,
                                            "Invalid username or password",
                                            exchange.getRequest().getPath().value(),
                                            traceId(exchange)));
                        }));
    }

    /**
     * What the current token says. Useful for debugging a permissions problem, and the quickest way
     * to show that the gateway really did verify the signature rather than trusting a header.
     */
    @GetMapping("/me")
    public Mono<CallerInfo> me(@AuthenticationPrincipal Jwt jwt) {
        return Mono.just(new CallerInfo(
                jwt.getSubject(),
                jwt.getClaimAsString(OmsClaims.ACCOUNT_ID),
                jwt.getClaimAsString(OmsClaims.DISPLAY_NAME),
                jwt.getClaimAsStringList(OmsClaims.ROLES),
                jwt.getIssuedAt(),
                jwt.getExpiresAt()));
    }

    public record CallerInfo(String subject, String accountId, String displayName,
                             List<String> roles, Instant issuedAt, Instant expiresAt) {
    }

    private static String traceId(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        return header != null ? header : exchange.getRequest().getId();
    }
}
