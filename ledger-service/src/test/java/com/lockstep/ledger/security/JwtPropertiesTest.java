package com.lockstep.ledger.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plain unit test: no Spring, no database. The app must refuse to start with a weak or missing secret. */
class JwtPropertiesTest {

    @Test
    void missingOrShortSecretIsRejected() {
        assertThatThrownBy(() -> new JwtProperties(null, "lockstep-ledger", Duration.ofHours(1)))
                .hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> new JwtProperties("", "lockstep-ledger", Duration.ofHours(1)))
                .hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> new JwtProperties("x".repeat(31), "lockstep-ledger", Duration.ofHours(1)))
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void thirtyTwoByteSecretIsAccepted() {
        assertThatCode(() -> new JwtProperties("x".repeat(32), "lockstep-ledger", Duration.ofHours(1)))
                .doesNotThrowAnyException();
    }

    @Test
    void zeroOrNegativeTtlIsRejected() {
        assertThatThrownBy(() -> new JwtProperties("x".repeat(32), "lockstep-ledger", Duration.ZERO))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtProperties("x".repeat(32), "lockstep-ledger", Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
