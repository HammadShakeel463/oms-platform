package com.oms.web.security;

import com.oms.common.error.ApiError;
import com.oms.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import com.oms.web.TraceIdFilter;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.time.Duration;
import java.util.List;

/**
 * The resource-server pieces every service needs, in one place.
 *
 * <p>Four services validating tokens means four chances to validate them slightly differently, and
 * the divergence would be invisible until an endpoint that should be restricted is not. Sharing the
 * validator and the error handlers makes "what counts as a valid token" a single definition.
 *
 * <p>What is deliberately <em>not</em> shared is the {@code SecurityFilterChain}: which paths need
 * which role is a property of the service, and a shared chain would either be a lowest common
 * denominator or a file that every service has to be careful not to break.
 */
public final class ResourceServerSupport {

    /**
     * Clock skew allowance for expiry and not-before.
     *
     * <p>Without it, two hosts a second apart reject each other's freshly minted tokens - which
     * presents as intermittent 401s that correlate with nothing and are extremely hard to diagnose.
     * Thirty seconds is small enough not to meaningfully extend a 15-minute token.
     */
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private ResourceServerSupport() {
    }

    /**
     * Everything a token must satisfy beyond having a valid signature.
     *
     * <p>A correct signature only proves the token was minted by the holder of the private key. It
     * says nothing about whether the token has expired, who issued it, or <b>who it was meant for</b>.
     *
     * <p>The audience check is the one most often omitted. Without it, a token this same issuer
     * minted for a different system - a reporting tool, a partner integration - would be accepted
     * here with whatever roles it carries. Checking {@code aud} is what makes a token usable only
     * against the system it was issued for.
     */
    public static OAuth2TokenValidator<Jwt> tokenValidator() {
        return new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(CLOCK_SKEW),
                JwtValidators.createDefaultWithIssuer(OmsClaims.ISSUER),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audience -> audience != null && audience.contains(OmsClaims.AUDIENCE)));
    }

    /**
     * 401 in the platform error contract.
     *
     * <p>Spring's default entry point returns an empty body with a {@code WWW-Authenticate} header.
     * That is correct HTTP and useless to a client that has one error handler for every failure the
     * platform produces.
     */
    public static AuthenticationEntryPoint unauthorizedEntryPoint(ObjectMapper objectMapper) {
        return (request, response, exception) -> write(response, objectMapper,
                ErrorCode.UNAUTHENTICATED,
                "A valid bearer token is required.",
                request.getRequestURI());
    }

    /**
     * 403 in the platform error contract.
     *
     * <p>Distinct from 401 on purpose: 401 means "authenticate", 403 means "authenticating
     * differently will not help". Collapsing them makes a client retry a login that cannot succeed.
     */
    public static AccessDeniedHandler forbiddenHandler(ObjectMapper objectMapper) {
        return (request, response, exception) -> write(response, objectMapper,
                ErrorCode.FORBIDDEN,
                "Your roles do not permit this operation.",
                request.getRequestURI());
    }

    private static void write(HttpServletResponse response, ObjectMapper objectMapper,
                              ErrorCode code, String message, String path) throws java.io.IOException {
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                ApiError.of(code, message, path, MDC.get(TraceIdFilter.MDC_KEY)));
    }
}
