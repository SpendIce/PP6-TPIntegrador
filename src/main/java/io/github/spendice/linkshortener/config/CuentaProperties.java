package io.github.spendice.linkshortener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuración del área de cuentas (prefijo {@code cuenta}, ADR 0008):
 * valores variables de usuarios, namespaces y sesiones, cada uno con su
 * variable de entorno en {@code application.yml}. Como en
 * {@link ShortenerProperties}, los defaults viven solo en
 * {@code application.yml} y un valor ausente o vacío corta el arranque.
 *
 * <ul>
 *   <li>{@code main-session-idle-timeout}: inactividad máxima que se
 *   graba en cada sesión principal al iniciar sesión con Google
 *   ({@code MAIN_SESSION_IDLE_TIMEOUT}, {@code P30D} por defecto).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "cuenta")
public record CuentaProperties(Duration mainSessionIdleTimeout) {

    public CuentaProperties {
        if (mainSessionIdleTimeout == null) {
            throw new IllegalArgumentException(
                    "cuenta.main-session-idle-timeout es obligatoria"
                            + " (MAIN_SESSION_IDLE_TIMEOUT, p. ej. P30D)");
        }
        if (mainSessionIdleTimeout.compareTo(Duration.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "cuenta.main-session-idle-timeout debe ser positiva y se recibió "
                            + mainSessionIdleTimeout);
        }
    }
}
