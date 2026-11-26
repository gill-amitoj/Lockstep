package com.lockstep.ledger.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * How requests are authenticated and which roles may call which URLs.
 *
 * Token flow: POST /auth/token checks username + password and returns a signed
 * JWT. Every other request sends "Authorization: Bearer <jwt>". Spring checks the
 * signature, expiry and issuer, then exposes the claims to controllers.
 *
 * Signing uses HS256 (one shared secret). Trade-off: simple to run, but every
 * service that can VERIFY tokens can also CREATE them. A production system
 * would use RS256 (private key only on the issuer, public key everywhere else)
 * or a dedicated identity provider.
 *
 * Two layers of authorization:
 *  1. Coarse, by URL and role, here.
 *  2. Fine, by ownership ("is this YOUR account?"), in the services via Caller.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProblemHandler problemHandler) throws Exception {
        http
                // CSRF protection defends cookie-based sessions. This API uses no cookies:
                // the browser never attaches the bearer token automatically, so CSRF is not possible.
                .csrf(csrf -> csrf.disable())
                // No HTTP session: every request carries its own token.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/auth/register", "/auth/token").permitAll()
                        // Spring forwards errors to /error internally; let that render the problem response.
                        .requestMatchers("/error").permitAll()
                        // API documentation is public: it describes the API, it grants no access to data.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/admin/**").hasRole(Role.ADMIN.name())
                        .requestMatchers(HttpMethod.POST, "/accounts").hasRole(Role.CUSTOMER.name())
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(problemHandler))
                // 401 and 403 happen in the filter chain, before any controller runs, so
                // @RestControllerAdvice never sees them. These handlers write problem+json instead.
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler));
        return http.build();
    }

    /** Maps the token's "roles" claim (["ADMIN"]) to Spring authorities (ROLE_ADMIN) for hasRole(). */
    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(Caller.ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties) {
        return NimbusJwtEncoder.withSecretKey(properties.secretKey())
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(properties.secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // Default validators check exp/nbf (with 60s clock skew); add the issuer check.
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    /** BCrypt: slow on purpose, with a random salt per password, so leaked hashes are expensive to crack. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
