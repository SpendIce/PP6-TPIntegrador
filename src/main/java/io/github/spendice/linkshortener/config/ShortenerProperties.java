package io.github.spendice.linkshortener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Configuración del despliegue (prefijo {@code shortener}): es el único
 * lugar donde viven los valores variables del servicio, cada uno con su
 * variable de entorno en {@code application.yml}.
 *
 * <ul>
 *   <li>{@code public-base-url}: dirección pública del servicio; compone
 *   la {@code shortUrl} de las respuestas. No se deriva de la solicitud
 *   ni queda fijada a localhost en producción.</li>
 *   <li>{@code link-duration}: duración de las nuevas asignaciones
 *   ({@code PT60M} por defecto).</li>
 *   <li>{@code own-origins}: orígenes propios adicionales a los que se
 *   rechazan destinos ({@code esquema://host[:puerto]}).</li>
 *   <li>{@code reserved-routes}: códigos que el generador nunca emite
 *   porque son rutas de la aplicación.</li>
 *   <li>{@code alias-alphabet}: alfabeto de los códigos de alias; alimenta
 *   tanto la generación como la validación del código recibido.</li>
 *   <li>{@code max-destination-length}: largo máximo de la URL de
 *   destino (8192 por defecto en {@code application.yml}); un valor no
 *   positivo es un error de configuración.</li>
 *   <li>{@code alias-route-pattern}: patrón de la ruta {@code GET /{alias}};
 *   tiene que ser consistente con {@code alias-alphabet}.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
        URI publicBaseUrl,
        Duration linkDuration,
        List<String> ownOrigins,
        List<String> reservedRoutes,
        String aliasAlphabet,
        int maxDestinationLength,
        String aliasRoutePattern) {

    /** Alfabeto por defecto: 58 símbolos sin los ambiguos 0, O, I ni l. */
    public static final String ALFABETO_POR_DEFECTO =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    /** Patrón por defecto del alias, consistente con el alfabeto por defecto. */
    public static final String PATRON_RUTA_POR_DEFECTO = "[1-9A-HJ-NP-Za-km-z]{1,32}";

    public ShortenerProperties {
        if (publicBaseUrl == null || publicBaseUrl.getHost() == null
                || publicBaseUrl.getScheme() == null) {
            throw new IllegalArgumentException(
                    "shortener.public-base-url debe ser una URL absoluta con esquema y host");
        }
        if (linkDuration == null) {
            linkDuration = Duration.ofMinutes(60);
        }
        if (linkDuration.isNegative() || linkDuration.isZero()) {
            throw new IllegalArgumentException(
                    "La duración de una asignación debe ser positiva");
        }
        ownOrigins = ownOrigins == null ? List.of() : List.copyOf(ownOrigins);
        reservedRoutes = reservedRoutes == null ? List.of() : List.copyOf(reservedRoutes);
        if (aliasAlphabet == null || aliasAlphabet.isBlank()) {
            aliasAlphabet = ALFABETO_POR_DEFECTO;
        }
        if (aliasAlphabet.chars().distinct().count() != aliasAlphabet.length()) {
            throw new IllegalArgumentException(
                    "El alfabeto de alias no puede tener símbolos repetidos");
        }
        if (maxDestinationLength <= 0) {
            throw new IllegalArgumentException(
                    "El largo máximo de la URL de destino debe ser positivo");
        }
        if (aliasRoutePattern == null || aliasRoutePattern.isBlank()) {
            aliasRoutePattern = PATRON_RUTA_POR_DEFECTO;
        }
    }
}
