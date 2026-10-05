package io.github.spendice.linkshortener.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AssignmentTest {

    private final Instant createdAt = Instant.parse("2026-10-05T12:00:00Z");
    private final Instant expiresAt = createdAt.plusSeconds(3600);
    private final Assignment assignment =
            Assignment.pending("7", "https://destino.example.com/doc", createdAt, expiresAt);

    @Test
    void estaVigenteAntesDelVencimiento() {
        assertThat(assignment.isActiveAt(createdAt)).isTrue();
        assertThat(assignment.isActiveAt(expiresAt.minusNanos(1))).isTrue();
    }

    @Test
    void dejaDeResolverExactamenteAlVencer() {
        assertThat(assignment.isActiveAt(expiresAt)).isFalse();
        assertThat(assignment.isActiveAt(expiresAt.plusNanos(1))).isFalse();
    }

    @Test
    void conservaElDestinoCompleto() {
        Assignment withFragment = Assignment.pending(
                "7",
                "https://usuario:clave@destino.example.com:8443/ruta?a=1&b=2#frag",
                createdAt,
                expiresAt);

        assertThat(withFragment.destination())
                .isEqualTo("https://usuario:clave@destino.example.com:8443/ruta?a=1&b=2#frag");
    }
}
