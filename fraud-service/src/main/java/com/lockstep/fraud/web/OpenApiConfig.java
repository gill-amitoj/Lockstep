package com.lockstep.fraud.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata for the fraud service. See the ledger's OpenApiConfig for how the pieces fit. */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI fraudOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Lockstep Fraud API")
                        .version("0.0.1")
                        .description("""
                                Read-only view of transfers flagged by the fraud rules. Flags are raised \
                                asynchronously from the ledger's TransferCompleted events on Kafka.

                                **Authentication:** an ADMIN token from the ledger's `POST /auth/token`.""")
                        .license(new License().name("MIT")))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
