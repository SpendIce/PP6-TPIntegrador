package io.github.spendice.linkshortener.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ExpirationPolicyTest {

    @Test
    void laAsignacionVenceExactamenteALos60Minutos() {
        ExpirationPolicy policy = new ExpirationPolicy(Duration.ofMinutes(60));
        Instant createdAt = Instant.parse("2026-10-05T12:00:00Z");

        assertThat(policy.expiresAt(createdAt))
                .isEqualTo(Instant.parse("2026-10-05T13:00:00Z"));
    }

    @Test
    void laDuracionEsConfigurableParaNuevasAsignaciones() {
        ExpirationPolicy policy = new ExpirationPolicy(Duration.ofMinutes(5));
        Instant createdAt = Instant.parse("2026-10-05T12:00:00Z");

        assertThat(policy.expiresAt(createdAt))
                .isEqualTo(createdAt.plus(Duration.ofMinutes(5)));
    }
}
