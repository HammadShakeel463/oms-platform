package com.oms.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Builds a service OpenAPI document with the platform bearer scheme already declared.
 *
 * <p>springdoc infers paths, parameters and schemas from the controllers. What it cannot infer is
 * the security scheme - so without this, each service publishes documentation that is accurate and
 * unusable: every try-it-out in Swagger UI returns 401 and the reader has no field to paste a token
 * into.
 *
 * <p>Shared rather than repeated four times, because "how you authenticate against this platform" is
 * one fact. A service that documented it differently would be documenting a different platform.
 */
public final class OmsOpenApi {

    public static final String BEARER_SCHEME = "bearerAuth";

    private OmsOpenApi() {
    }

    public static OpenAPI bearerSecured(String title, String description) {
        return new OpenAPI()
                .info(new Info()
                        .title(title)
                        .version("1.0.0")
                        .description(description + """

                                All endpoints require a bearer token. Obtain one from the gateway:

                                    POST http://localhost:8080/auth/token
                                    {"username": "trader1", "password": "trader1-password"}

                                Then use Authorize above, or send Authorization: Bearer <token>.
                                """))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token from POST /auth/token on the gateway")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
