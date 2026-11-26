package com.lockstep.ledger.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Top-level OpenAPI metadata. springdoc builds the endpoint list itself from
 * the controllers; this adds the title, description and the bearer-token scheme,
 * so Swagger UI shows an "Authorize" button that sends "Authorization: Bearer ...".
 */
@Configuration
public class OpenApiConfig {

    static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI ledgerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Lockstep Ledger API")
                        .version("0.0.1")
                        .description("""
                                Double-entry payments ledger. Money is always an integer number of cents (CAD).

                                **Authentication:** call `POST /auth/token` with a username and password, then \
                                send `Authorization: Bearer <access_token>`. In Swagger UI, use **Authorize**.

                                **Errors** are RFC 9457 `application/problem+json`, with a `correlationId` that \
                                matches the `X-Correlation-Id` response header and the server logs.""")
                        .license(new License().name("MIT")))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                // Applies to every operation; /auth/* override it with an empty requirement.
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
