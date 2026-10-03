package com.oms.web.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.web.TraceIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a token has to satisfy beyond a valid signature, and what a rejection looks like on the
 * wire.
 *
 * <p>The audience case is the one worth having a test for. A correct signature only proves the
 * token was minted by the holder of the private key - it says nothing about which system it was
 * minted *for*. Without the {@code aud} check, a token this same issuer created for a reporting
 * tool would be accepted here with whatever roles it carries.
 */
class ResourceServerSupportTest {

    private final OAuth2TokenValidator<Jwt> validator = ResourceServerSupport.tokenValidator();

    /**
     * Built the way Boot builds the one it injects, which registers the JSR-310 module.
     *
     * <p>Not {@code new ObjectMapper()}: a bare mapper cannot serialise the {@code Instant} on
     * {@link com.oms.common.error.ApiError}, so these handlers would throw while rendering a 401 -
     * turning an authentication failure into a 500 with no body. The production path is safe
     * because the injected mapper is Boot's; using a bare one here would have tested a mapper the
     * platform never uses.
     */
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("a token with the right issuer, audience and timestamps is accepted")
    void acceptsAValidToken() {
        assertThat(validator.validate(jwt(builder -> { })).hasErrors()).isFalse();
    }

    @Test
    @DisplayName("an expired token is rejected")
    void rejectsExpired() {
        OAuth2TokenValidatorResult result = validator.validate(jwt(b -> b
                .issuedAt(Instant.now().minus(2, ChronoUnit.HOURS))
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS))));

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    @DisplayName("a token from another issuer is rejected")
    void rejectsForeignIssuer() {
        assertThat(validator.validate(jwt(b -> b.issuer("https://some-other-idp.example")))
                .hasErrors()).isTrue();
    }

    @Test
    @DisplayName("a token minted for a different audience is rejected - the check people omit")
    void rejectsWrongAudience() {
        assertThat(validator.validate(jwt(b -> b.audience(List.of("oms-reporting-tool"))))
                .hasErrors()).isTrue();
    }

    @Test
    @DisplayName("a token with no audience claim at all is rejected, not treated as universal")
    void rejectsMissingAudience() {
        Jwt noAudience = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("trader1")
                .issuer(OmsClaims.ISSUER)
                .claim(OmsClaims.ACCOUNT_ID, "ACC-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(15, ChronoUnit.MINUTES))
                .build();

        assertThat(validator.validate(noAudience).hasErrors()).isTrue();
    }

    @Test
    @DisplayName("a token that expired seconds ago is still accepted: clock skew is allowed for")
    void allowsClockSkew() {
        OAuth2TokenValidatorResult result = validator.validate(jwt(b -> b
                .issuedAt(Instant.now().minus(15, ChronoUnit.MINUTES))
                .expiresAt(Instant.now().minusSeconds(5))));

        assertThat(result.hasErrors())
                .as("without skew allowance, two hosts a second apart reject each other's tokens")
                .isFalse();
    }

    @Test
    @DisplayName("an audience list containing ours among others is accepted")
    void acceptsAudienceAmongOthers() {
        assertThat(validator.validate(jwt(b -> b.audience(List.of("something-else", OmsClaims.AUDIENCE))))
                .hasErrors()).isFalse();
    }

    @Test
    @DisplayName("the 401 entry point writes the platform ApiError, not an empty body")
    void unauthorizedWritesTheErrorContract() throws Exception {
        MDC.put(TraceIdFilter.MDC_KEY, "trace-401");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResourceServerSupport.unauthorizedEntryPoint(objectMapper)
                .commence(request, response, null);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/json");

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(body.get("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("path").asText()).isEqualTo("/api/v1/orders");
        assertThat(body.get("traceId").asText()).isEqualTo("trace-401");
    }

    @Test
    @DisplayName("403 is distinct from 401: authenticating differently will not help")
    void forbiddenIsItsOwnStatus() throws Exception {
        MDC.put(TraceIdFilter.MDC_KEY, "trace-403");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/books/HBL");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResourceServerSupport.forbiddenHandler(objectMapper).handle(request, response, null);

        assertThat(response.getStatus()).isEqualTo(403);
        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(body.get("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(body.get("traceId").asText()).isEqualTo("trace-403");
    }

    private static Jwt jwt(Consumer<Jwt.Builder> customiser) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("trader1")
                .issuer(OmsClaims.ISSUER)
                .audience(List.of(OmsClaims.AUDIENCE))
                .claim(OmsClaims.ACCOUNT_ID, "ACC-1")
                .claim(OmsClaims.ROLES, List.of("TRADER"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(15, ChronoUnit.MINUTES));
        customiser.accept(builder);
        return builder.build();
    }
}
