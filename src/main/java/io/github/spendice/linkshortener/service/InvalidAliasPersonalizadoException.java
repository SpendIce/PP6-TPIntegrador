package io.github.spendice.linkshortener.service;

/**
 * Rechazo de un alias personalizado propuesto para un enlace personal
 * (issue #21). El {@link Reason} coincide con los códigos de error
 * publicados en el contrato OpenAPI: formato inválido
 * ({@code ALIAS_INVALID}) o alias ya vigente para el mismo usuario
 * ({@code ALIAS_TAKEN}).
 */
public final class InvalidAliasPersonalizadoException extends RuntimeException {

    public enum Reason {
        ALIAS_INVALID,
        ALIAS_TAKEN
    }

    private final Reason reason;

    public InvalidAliasPersonalizadoException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
