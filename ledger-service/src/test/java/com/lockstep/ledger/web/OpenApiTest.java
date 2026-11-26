package com.lockstep.ledger.web;

import com.lockstep.ledger.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The OpenAPI document is public and describes the API as it really behaves. */
class OpenApiTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void specIsPublicAndListsTheEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Lockstep Ledger API"))
                .andExpect(jsonPath("$.paths['/transfers'].post").exists())
                .andExpect(jsonPath("$.paths['/accounts/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/admin/reconciliation-runs'].post").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
    }

    @Test
    void specDocumentsTheRequiredIdempotencyHeaderAndRealResponseShape() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/transfers'].post.parameters[?(@.name == 'Idempotency-Key')].required",
                        hasItem(true)))
                .andExpect(jsonPath("$.paths['/transfers'].post.responses['201'].content['application/json'].schema['$ref']")
                        .value("#/components/schemas/TransferResponse"))
                // The authenticated principal is filled in by Spring, not sent by the client.
                .andExpect(jsonPath("$.paths['/transfers'].post.parameters[?(@.name == 'jwt')]").isEmpty());
    }

    @Test
    void authEndpointsNeedNoToken() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/auth/token'].post.security").isEmpty());
    }

    @Test
    void swaggerUiIsServed() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
