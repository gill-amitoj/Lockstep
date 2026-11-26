package com.lockstep.ledger.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * JWT settings, bound from lockstep.jwt.* (see application.properties).
 * The secret comes from the JWT_SECRET environment variable and is never committed.
 *
 * The app refuses to start without a strong enough secret: failing loudly at
 * startup is better than running with a guessable signing key.
 */
@ConfigurationProperties("lockstep.jwt")
public record JwtProperties(String secret, String issuer, Duration ttl) {

    /** HS256 needs a key at least as long as its 256-bit output. */
    static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set and at least " + MIN_SECRET_BYTES + " bytes long");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("lockstep.jwt.issuer must be set");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalStateException("lockstep.jwt.ttl must be a positive duration");
        }
    }

    public SecretKey secretKey() {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
