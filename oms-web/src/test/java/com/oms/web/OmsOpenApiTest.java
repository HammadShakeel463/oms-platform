package com.oms.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared OpenAPI document shape.
 *
 * <p>Worth a test for one reason: springdoc infers paths and schemas from the controllers but
 * cannot infer the security scheme. If this declaration is wrong, every service publishes
 * documentation that is accurate and unusable - Swagger UI shows no Authorize button and every
 * try-it-out returns 401. That is a failure nothing else in the build would catch.
 */
class OmsOpenApiTest {

    @Test
    @DisplayName("declares an HTTP bearer/JWT scheme, which is what gives Swagger UI an Authorize button")
    void declaresTheBearerScheme() {
        OpenAPI api = OmsOpenApi.bearerSecured("order-service", "Order intake");

        SecurityScheme scheme = api.getComponents().getSecuritySchemes().get(OmsOpenApi.BEARER_SCHEME);

        assertThat(scheme).isNotNull();
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(scheme.getScheme()).isEqualTo("bearer");
        assertThat(scheme.getBearerFormat()).isEqualTo("JWT");
    }

    @Test
    @DisplayName("applies the scheme document-wide, so every operation inherits it")
    void appliesTheSchemeGlobally() {
        OpenAPI api = OmsOpenApi.bearerSecured("position-service", "Positions and P&L");

        assertThat(api.getSecurity())
                .as("a declared-but-unapplied scheme leaves every operation looking anonymous")
                .hasSize(1);
        assertThat(api.getSecurity().get(0)).containsKey(OmsOpenApi.BEARER_SCHEME);
    }

    @Test
    @DisplayName("carries the service title and tells the reader where to get a token")
    void documentsHowToAuthenticate() {
        OpenAPI api = OmsOpenApi.bearerSecured("market-data-service", "Quotes and instruments");

        assertThat(api.getInfo().getTitle()).isEqualTo("market-data-service");
        assertThat(api.getInfo().getVersion()).isEqualTo("1.0.0");
        assertThat(api.getInfo().getDescription())
                .startsWith("Quotes and instruments")
                .contains("/auth/token");
    }
}
