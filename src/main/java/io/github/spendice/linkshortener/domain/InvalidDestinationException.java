package io.github.spendice.linkshortener.domain;

/**
 * Rechazo de una URL de destino. El {@link Reason} coincide con los
 * códigos de error publicados en el contrato OpenAPI.
 */
public final class InvalidDestinationException extends RuntimeException {

    public enum Reason {
        EMPTY_DESTINATION,
        DESTINATION_TOO_LONG,
        MALFORMED_DESTINATION,
        UNSUPPORTED_SCHEME,
        OWN_ORIGIN
    }

    private final Reason reason;

    public InvalidDestinationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
