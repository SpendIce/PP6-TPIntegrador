package io.github.spendice.linkshortener.domain;

import io.github.spendice.linkshortener.domain.InvalidDestinationException.Reason;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Set;

/**
 * Reglas de aceptación de la URL de destino: URI absoluta http/https de
 * hasta 8.192 caracteres, sin espacios ni caracteres internacionales sin
 * codificar, y fuera de los orígenes propios del servicio. No consulta la
 * disponibilidad del destino ni modifica la entrada: el destino aceptado
 * se persiste verbatim.
 */
public final class DestinationValidator {

    public static final int MAX_LENGTH = 8192;

    private final Set<ServiceOrigin> ownOrigins;

    public DestinationValidator(Collection<ServiceOrigin> ownOrigins) {
        this.ownOrigins = Set.copyOf(ownOrigins);
    }

    public void validate(String destination) {
        if (destination == null || destination.isBlank()) {
            throw new InvalidDestinationException(Reason.EMPTY_DESTINATION,
                    "La URL de destino es obligatoria.");
        }
        if (destination.length() > MAX_LENGTH) {
            throw new InvalidDestinationException(Reason.DESTINATION_TOO_LONG,
                    "La URL de destino supera el máximo de " + MAX_LENGTH + " caracteres.");
        }
        if (!destination.chars().allMatch(c -> c <= 0x7F)) {
            throw new InvalidDestinationException(Reason.MALFORMED_DESTINATION,
                    "Los caracteres internacionales deben ir codificados (percent-encoding o dominio punycode).");
        }
        URI uri;
        try {
            uri = new URI(destination);
        } catch (URISyntaxException e) {
            throw new InvalidDestinationException(Reason.MALFORMED_DESTINATION,
                    "La URL de destino no tiene un formato válido: " + e.getReason());
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new InvalidDestinationException(Reason.UNSUPPORTED_SCHEME,
                    "La URL de destino debe usar esquema http o https.");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new InvalidDestinationException(Reason.MALFORMED_DESTINATION,
                    "La URL de destino no indica un host válido.");
        }
        int port = uri.getPort() >= 0 ? uri.getPort() : ServiceOrigin.defaultPort(scheme);
        if (ownOrigins.stream().anyMatch(origin -> origin.matches(uri.getHost(), port))) {
            throw new InvalidDestinationException(Reason.OWN_ORIGIN,
                    "La URL de destino pertenece al propio acortador.");
        }
    }
}
