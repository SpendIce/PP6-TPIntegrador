package io.github.spendice.linkshortener.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Configuración del despliegue (prefijo {@code shortener}):
 *
 * <ul>
 *   <li>{@code public-base-url}: dirección pública del servicio; compone
 *   la {@code shortUrl} de las respuestas. No se deriva de la solicitud
 *   ni queda fijada a localhost en producción.</li>
 *   <li>{@code own-origins}: orígenes propios adicionales a los que se
 *   rechazan destinos ({@code esquema://host[:puerto]}).</li>
 *   <li>{@code link-duration}: duración de las nuevas asignaciones
 *   ({@code PT60M} por defecto).</li>
 *   <li>{@code reserved-routes}: códigos que el generador nunca emite
 *   porque son rutas de la aplicación.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
        URI publicBaseUrl,
        Duration linkDuration,
        List<String> ownOrigins,
        List<String> reservedRoutes) {

    public ShortenerProperties {
        if (publicBaseUrl == null || publicBaseUrl.getHost() == null
                || publicBaseUrl.getScheme() == null) {
            throw new IllegalArgumentException(
                    "shortener.public-base-url debe ser una URL absoluta con esquema y host");
        }
        if (linkDuration == null) {
            linkDuration = Duration.ofMinutes(60);
        }
        ownOrigins = ownOrigins == null ? List.of() : List.copyOf(ownOrigins);
        reservedRoutes = reservedRoutes == null ? List.of() : List.copyOf(reservedRoutes);
    }
}
