package com.lockstep.ledger.security;

import com.lockstep.ledger.IntegrationTest;
import com.lockstep.ledger.domain.Account;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may call what. Every token here is a real signed JWT; nothing is mocked. */
class AuthorizationApiTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    // ---- 401: no valid token --------------------------------------------------

    @Test
    void noTokenReturns401ProblemJson() throws Exception {
        mockMvc.perform(get("/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://lockstep.example/problems/unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        JwtEncoder attackerEncoder = NimbusJwtEncoder.withSecretKey(new SecretKeySpec(
                        "an-attacker-key-that-is-long-enough-for-hs256!".getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .algorithm(MacAlgorithm.HS256)
                .build();
        String forged = encode(attackerEncoder, "lockstep-ledger", Instant.now().plusSeconds(3600), "ADMIN");

        mockMvc.perform(get("/admin/audit-events").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        // Beyond the decoder's 60-second clock-skew allowance.
        String expired = encode(jwtEncoder, "lockstep-ledger", Instant.now().minus(Duration.ofMinutes(5)), "CUSTOMER");

        mockMvc.perform(get("/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() throws Exception {
        String foreign = encode(jwtEncoder, "some-other-service", Instant.now().plusSeconds(3600), "CUSTOMER");

        mockMvc.perform(get("/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer " + foreign))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void garbageTokenIsRejected() throws Exception {
        mockMvc.perform(get("/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // ---- 403: valid token, wrong role -----------------------------------------

    @Test
    void customerCannotUseAdminEndpoints() throws Exception {
        Account alice = openFundedAccount(0);

        mockMvc.perform(get("/admin/audit-events").header(HttpHeaders.AUTHORIZATION, bearerFor(alice)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://lockstep.example/problems/forbidden"));
        mockMvc.perform(post("/admin/reconciliation-runs").header(HttpHeaders.AUTHORIZATION, bearerFor(alice)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCannotOpenCustomerAccounts() throws Exception {
        mockMvc.perform(post("/accounts").header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isForbidden());
    }

    // ---- ownership: valid token, not your account -----------------------------

    @Test
    void customerGets404ForSomeoneElsesAccount() throws Exception {
        Account alice = openFundedAccount(500);
        Account eve = openFundedAccount(0);

        mockMvc.perform(get("/accounts/{id}", alice.getId()).header(HttpHeaders.AUTHORIZATION, bearerFor(eve)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/accounts/{id}/entries", alice.getId()).header(HttpHeaders.AUTHORIZATION, bearerFor(eve)))
                .andExpect(status().isNotFound());
    }

    @Test
    void customerCannotTransferFromSomeoneElsesAccount() throws Exception {
        Account alice = openFundedAccount(500);
        Account eve = openFundedAccount(0);

        mockMvc.perform(post("/transfers")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(eve))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccountId": %d, "toAccountId": %d, "amountCents": 500}
                                """.formatted(alice.getId(), eve.getId())))
                .andExpect(status().isNotFound());

        assertThat(balanceOf(alice.getId())).isEqualTo(500);
        assertThat(balanceOf(eve.getId())).isZero();
    }

    @Test
    void adminCanReadAnyAccountAndDepositFromSystem() throws Exception {
        Account alice = openFundedAccount(0);
        Long systemId = accountService.getSystemAccount().getId();

        mockMvc.perform(post("/transfers")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccountId": %d, "toAccountId": %d, "amountCents": 7500}
                                """.formatted(systemId, alice.getId())))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/accounts/{id}", alice.getId()).header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceCents").value(7500));
    }

    private static String encode(JwtEncoder encoder, String issuer, Instant expiresAt, String role) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("someone")
                .issuedAt(expiresAt.minus(Duration.ofHours(1)))
                .expiresAt(expiresAt)
                .claim(Caller.ROLES_CLAIM, List.of(role))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
