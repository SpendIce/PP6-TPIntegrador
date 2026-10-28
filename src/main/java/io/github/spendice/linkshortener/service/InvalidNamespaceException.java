package io.github.spendice.linkshortener.service;

/**
 * Rechazo de un namespace propuesto. El {@link Reason} coincide con los
 * códigos de error publicados en el contrato OpenAPI: cada motivo
 * (formato inválido, nombre reservado, nombre tomado o usuario que
 * todavía no eligió namespace) responde con su propio código.
 */
public final class InvalidNamespaceException extends RuntimeException {

    public enum Reason {
        NAMESPACE_INVALID,
        NAMESPACE_RESERVED,
        NAMESPACE_TAKEN,
        NAMESPACE_NOT_CHOSEN
    }

    private final Reason reason;

    public InvalidNamespaceException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
