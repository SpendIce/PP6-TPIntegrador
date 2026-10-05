package io.github.spendice.linkshortener.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * Código público reutilizable del enlace acortado. No es la identidad de
 * una asignación: conserva solo la referencia a la asignación actual o a
 * la última conocida (ADR 0002). La referencia puede seguir apuntando a
 * una asignación vencida hasta la próxima reutilización.
 */
public record Alias(String code, Long currentAssignmentId) {

    public Alias {
        Objects.requireNonNull(code, "code");
        if (!AliasAlphabet.isCode(code)) {
            throw new IllegalArgumentException("Código de alias fuera del alfabeto: " + code);
        }
    }

    public Optional<Long> currentAssignment() {
        return Optional.ofNullable(currentAssignmentId);
    }
}
