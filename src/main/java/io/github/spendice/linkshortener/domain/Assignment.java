package io.github.spendice.linkshortener.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Asociación temporal de un alias con una URL de destino. Tiene
 * identidad propia independiente del alias (ADR 0002): cada creación
 * produce una asignación nueva aunque repita el destino, y las anteriores
 * se conservan en el historial (ADR 0003).
 */
public record Assignment(Long id, String aliasCode, String destination, Instant createdAt, Instant expiresAt) {

    public Assignment {
        Objects.requireNonNull(aliasCode, "aliasCode");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** Asignación aún sin identificador, previa a su persistencia. */
    public static Assignment pending(String aliasCode, String destination, Instant createdAt, Instant expiresAt) {
        return new Assignment(null, aliasCode, destination, createdAt, expiresAt);
    }

    /**
     * La vigencia se evalúa por comparación de instantes: al alcanzar
     * {@code expiresAt} la asignación ya no resuelve.
     */
    public boolean isActiveAt(Instant instant) {
        return instant.isBefore(expiresAt);
    }
}
