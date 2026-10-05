package io.github.spendice.linkshortener.domain;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Origen propio del servicio, identificado por host y puerto efectivo.
 * El esquema no distingue orígenes: el servicio de la demo es HTTP y un
 * destino al mismo host y puerto pertenece al acortador independientemente
 * del esquema declarado.
 */
public record ServiceOrigin(String host, int port) {

    public ServiceOrigin {
        Objects.requireNonNull(host, "host");
        host = normalize(host);
    }

    public static ServiceOrigin of(URI uri) {
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Origen sin host: " + uri);
        }
        int port = uri.getPort() >= 0 ? uri.getPort() : defaultPort(uri.getScheme());
        return new ServiceOrigin(uri.getHost(), port);
    }

    public static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    public boolean matches(String candidateHost, int candidatePort) {
        return port == candidatePort && host.equals(normalize(candidateHost));
    }

    private static String normalize(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
