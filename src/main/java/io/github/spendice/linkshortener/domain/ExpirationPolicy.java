package io.github.spendice.linkshortener.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Regla temporal de las asignaciones: el vencimiento se calcula una sola
 * vez, al crear, como {@code createdAt + duration}. La duración es un
 * punto de variabilidad real (experimento de adaptación); las
 * asignaciones ya persistidas conservan su propio {@code vence_en}.
 */
public final class ExpirationPolicy {

    private final Duration duration;

    public ExpirationPolicy(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("La duración de una asignación debe ser positiva");
        }
        this.duration = duration;
    }

    public Instant expiresAt(Instant createdAt) {
        return createdAt.plus(duration);
    }
}
