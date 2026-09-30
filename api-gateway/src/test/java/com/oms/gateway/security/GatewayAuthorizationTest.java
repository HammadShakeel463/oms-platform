package com.oms.gateway.security;

import com.oms.gateway.auth.TokenIssuer;
import com.oms.gateway.auth.UserStore;
import com.oms.web.security.OmsRoles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway authorisation matrix, exercised through the real filter chain.
 *
 * <p>This is a full {@code @SpringBootTest} rather than a slice, because the thing under test is
 * the interaction between the security chain, the token decoder and the route table - none of which
 * a slice assembles. Downstream services are not running, so a request that gets <em>past</em>
 * authorisation fails to connect. That is the assertion: <b>503 means the request was authorised
 * and routed</b>, and 401 or 403 means it was stopped at the edge. Distinguishing "stopped by
 * security" from "allowed through" is exactly what this test needs to establish.
 *
 * <p>Tokens are minted by the real {@link TokenIssuer} and verified by the real decoder, so the
 * signature path is genuinely exercised rather than stubbed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayAuthorizationTest {

    @Autowired
    private WebTestClient webClient;

    @Autowired
    private TokenIssuer tokenIssuer;

    @Autowired
    private UserStore userStore;

    private String tokenFor(String username) {
        return tokenIssuer.issue(userStore.find(username).orElseThrow()).accessToken();
    }

    private WebTestClient.RequestHeadersSpec<?> as(String username, String path) {
        return webClient.get().uri(path)
                .header("Authorization", "Bearer " + tokenFor(username));
    }

    /** Authorised and routed, but the downstream service is not running in this test. */
    private static boolean wasRouted(int status) {
        return status >= 500;
    }

    // =================================================================================
    //  Public endpoints
    // =================================================================================

    @Test
    @DisplayName("the token endpoint is public - it has to be")
    void tokenEndpointIsPublic() {
        webClient.post().uri("/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "trader1", "password", "trader1-password"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").isNotEmpty()
                .jsonPath("$.tokenType").isEqualTo("Bearer")
                .jsonPath("$.accountId").isEqualTo("ACC-TRADER-1")
                .jsonPath("$.roles[0]").isEqualTo(OmsRoles.TRADER)
                .jsonPath("$.expiresIn").isEqualTo(900);
    }

    @Test
    @DisplayName("bad credentials are 401 in the platform error contract, with no hint which part failed")
    void badCredentialsAre401() {
        webClient.post().uri("/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "trader1", "password", "wrong"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED")
                .jsonPath("$.message").isEqualTo("Invalid username or password");
    }

    @Test
    @DisplayName("the JWKS endpoint is public and publishes only the public key")
    void jwksIsPublicAndSafe() {
        webClient.get().uri("/oauth2/jwks")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.keys[0].kty").isEqualTo("RSA")
                .jsonPath("$.keys[0].n").isNotEmpty()
                .jsonPath("$.keys[0].kid").isNotEmpty()
                // The private exponent must never appear here.
                .jsonPath("$.keys[0].d").doesNotExist();
    }

    @Test
    @DisplayName("health probes are public, so a rollout can become ready")
    void healthIsPublic() {
        webClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("metrics are NOT public - they carry order rates and account activity")
    void metricsRequireAdmin() {
        webClient.get().uri("/actuator/metrics")
                .exchange()
                .expectStatus().isUnauthorized();

        int traderStatus = as("trader1", "/actuator/metrics")
                .exchange().returnResult(String.class).getStatus().value();
        assertThat(traderStatus)
                .as("a trader has no business reading platform metrics")
                .isEqualTo(403);

        as("admin", "/actuator/metrics").exchange().expectStatus().isOk();
    }

    // =================================================================================
    //  Authentication
    // =================================================================================

    @Test
    @DisplayName("no token is 401")
    void noTokenIs401() {
        webClient.get().uri("/api/v1/orders")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("a garbage token is 401, not 500")
    void garbageTokenIs401() {
        webClient.get().uri("/api/v1/orders")
                .header("Authorization", "Bearer not-a-jwt")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("a token signed by a different key is 401")
    void foreignlySignedTokenIs401() {
        // Minted with a key this gateway has never seen - the exact shape of a forgery attempt.
        var foreignKeys = com.oms.gateway.auth.SigningKeys.generate();
        var foreignIssuer = new TokenIssuer(foreignKeys,
                new com.oms.gateway.auth.AuthProperties(java.time.Duration.ofMinutes(15),
                        java.util.List.of()),
                java.time.Clock.systemUTC());
        String forged = foreignIssuer.issue(userStore.find("admin").orElseThrow()).accessToken();

        webClient.get().uri("/api/v1/orders")
                .header("Authorization", "Bearer " + forged)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // =================================================================================
    //  Authorisation matrix
    // =================================================================================

    @Test
    @DisplayName("TRADER may place an order")
    void traderMayPlaceOrders() {
        int status = webClient.post().uri("/api/v1/orders")
                .header("Authorization", "Bearer " + tokenFor("trader1"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("clientOrderId", "cl-1", "symbol", "HBL", "side", "BUY",
                        "orderType", "LIMIT", "timeInForce", "DAY",
                        "limitPrice", "172.4500", "quantity", 100))
                .exchange()
                .returnResult(String.class).getStatus().value();

        assertThat(wasRouted(status))
                .as("authorised and routed; order-service is not running in this test (got %d)", status)
                .isTrue();
    }

    @Test
    @DisplayName("RISK may NOT place an order - separation of duties")
    void riskMayNotPlaceOrders() {
        webClient.post().uri("/api/v1/orders")
                .header("Authorization", "Bearer " + tokenFor("riskuser"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("clientOrderId", "cl-1", "symbol", "HBL", "side", "BUY",
                        "orderType", "LIMIT", "timeInForce", "DAY",
                        "limitPrice", "172.4500", "quantity", 100))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("RISK may NOT cancel an order either")
    void riskMayNotCancelOrders() {
        webClient.delete().uri("/api/v1/orders/{id}", java.util.UUID.randomUUID())
                .header("Authorization", "Bearer " + tokenFor("riskuser"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    @DisplayName("RISK may read orders and positions")
    void riskMayRead() {
        assertThat(wasRouted(as("riskuser", "/api/v1/orders")
                .exchange().returnResult(String.class).getStatus().value())).isTrue();
        assertThat(wasRouted(as("riskuser", "/api/v1/positions")
                .exchange().returnResult(String.class).getStatus().value())).isTrue();
    }

    @Test
    @DisplayName("book depth is ADMIN only - it reveals every resting order")
    void bookDepthIsAdminOnly() {
        as("trader1", "/api/v1/books/HBL").exchange().expectStatus().isForbidden();
        as("riskuser", "/api/v1/books/HBL").exchange().expectStatus().isForbidden();

        assertThat(wasRouted(as("admin", "/api/v1/books/HBL")
                .exchange().returnResult(String.class).getStatus().value()))
                .as("ADMIN is authorised; matching-engine is not running in this test")
                .isTrue();
    }

    @Test
    @DisplayName("market data is readable by any authenticated caller")
    void marketDataNeedsOnlyAuthentication() {
        for (String user : new String[]{"trader1", "riskuser", "admin"}) {
            int status = as(user, "/api/v1/instruments")
                    .exchange().returnResult(String.class).getStatus().value();
            assertThat(wasRouted(status))
                    .as("%s should be authorised for market data (got %d)", user, status)
                    .isTrue();
        }
    }

    // =================================================================================
    //  Caller introspection
    // =================================================================================

    @Test
    @DisplayName("/auth/me reflects the verified token, proving the gateway decoded it")
    void authMeReflectsTheToken() {
        as("trader1", "/auth/me")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.subject").isEqualTo("trader1")
                .jsonPath("$.accountId").isEqualTo("ACC-TRADER-1")
                .jsonPath("$.roles[0]").isEqualTo(OmsRoles.TRADER)
                .jsonPath("$.expiresAt").isNotEmpty();
    }

    @Test
    @DisplayName("/auth/me without a token is 401")
    void authMeRequiresAToken() {
        webClient.get().uri("/auth/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
