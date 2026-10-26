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
 * <p>Los valores por defecto viven solo en {@code application.yml}; este
 * record no los repite. Un valor ausente o vacío (p. ej.
 * {@code LINK_DURATION=} en el entorno) es un error de configuración que
 * corta el arranque, en lugar de caer en silencio a otro valor.</p>
 *
 * <ul>
 *   <li>{@code public-base-url}: dirección pública del servicio; compone
 *   la {@code shortUrl} de las respuestas. No se deriva de la solicitud
 *   ni queda fijada a localhost en producción.</li>
 *   <li>{@code link-duration}: duración de las nuevas asignaciones; como
 *   mínimo un minuto.</li>
 *   <li>{@code own-origins}: orígenes propios adicionales a los que se
 *   rechazan destinos ({@code esquema://host[:puerto]}).</li>
 *   <li>{@code reserved-routes}: códigos que el generador nunca emite
 *   porque son rutas de la aplicación.</li>
 *   <li>{@code alias-alphabet}: alfabeto de los códigos de alias; alimenta
 *   tanto la generación como la validación del código recibido.</li>
 *   <li>{@code max-destination-length}: largo máximo de la URL de
 *   destino; un valor no positivo es un error de configuración.</li>
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

    /**
     * Piso de {@code link-duration}. Además de acotar vencimientos
     * absurdos, detecta el error de escribir un número sin unidad:
     * Spring lo interpreta en milisegundos ({@code 40} son 40 ms).
     */
    static final Duration DURACION_MINIMA = Duration.ofMinutes(1);

    public ShortenerProperties {
        if (publicBaseUrl == null || publicBaseUrl.getHost() == null
                || publicBaseUrl.getScheme() == null) {
            throw new IllegalArgumentException(
                    "shortener.public-base-url debe ser una URL absoluta con esquema y host");
        }
        if (linkDuration == null) {
            throw new IllegalArgumentException(
                    "shortener.link-duration es obligatoria (LINK_DURATION, p. ej. PT60M)");
        }
        if (linkDuration.compareTo(DURACION_MINIMA) < 0) {
            throw new IllegalArgumentException(
                    "shortener.link-duration debe ser de al menos un minuto y se recibió "
                            + linkDuration + "; un número sin unidad se interpreta en"
                            + " milisegundos: usar PT40M o 40m para 40 minutos");
        }
        ownOrigins = ownOrigins == null ? List.of() : List.copyOf(ownOrigins);
        reservedRoutes = reservedRoutes == null ? List.of() : List.copyOf(reservedRoutes);
        if (aliasAlphabet == null || aliasAlphabet.isBlank()) {
            throw new IllegalArgumentException(
                    "shortener.alias-alphabet es obligatorio (ALIAS_ALPHABET)");
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
            throw new IllegalArgumentException(
                    "shortener.alias-route-pattern es obligatorio (ALIAS_ROUTE_PATTERN)");
        }
    }
}
