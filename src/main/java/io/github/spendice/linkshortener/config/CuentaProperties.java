package io.github.spendice.linkshortener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

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
 *   <li>{@code namespace-reserved-names}: nombres que ningún usuario
 *   puede elegir como namespace porque chocan con rutas del sistema
 *   ({@code NAMESPACE_RESERVED_NAMES}, {@code api,login,logout,vincular,oauth2,error}
 *   por defecto).</li>
 *   <li>{@code namespace-grace-period}: tiempo que un namespace anterior
 *   queda reservado para su dueño después de un cambio
 *   ({@code NAMESPACE_GRACE_PERIOD}, {@code PT1H} por defecto). Debe ser
 *   positivo; el fin se calcula al cambiar y modificar esta configuración
 *   no altera las gracias en curso.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "cuenta")
public record CuentaProperties(Duration mainSessionIdleTimeout,
                               List<String> namespaceReservedNames,
                               Duration namespaceGracePeriod) {

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
        // La sesión guarda la inactividad en segundos enteros: un valor que
        // trunque a 0 vence al instante y uno que desborde un int pierde el
        // vencimiento. Se rechaza en el arranque en vez de corromper la sesión.
        long segundos = mainSessionIdleTimeout.getSeconds();
        if (segundos < 1 || segundos > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "cuenta.main-session-idle-timeout debe durar entre 1 segundo y "
                            + Integer.MAX_VALUE + " segundos y se recibió "
                            + mainSessionIdleTimeout);
        }
        if (namespaceReservedNames == null || namespaceReservedNames.isEmpty()) {
            throw new IllegalArgumentException(
                    "cuenta.namespace-reserved-names es obligatoria"
                            + " (NAMESPACE_RESERVED_NAMES, p. ej. api,login,logout)");
        }
        // La comparación es sin distinguir mayúsculas: se guarda la lista
        // normalizada para que el service no repita la conversión.
        namespaceReservedNames = namespaceReservedNames.stream()
                .map(nombre -> nombre == null ? "" : nombre.strip().toLowerCase(Locale.ROOT))
                .filter(nombre -> !nombre.isEmpty())
                .distinct()
                .toList();
        if (namespaceReservedNames.isEmpty()) {
            throw new IllegalArgumentException(
                    "cuenta.namespace-reserved-names no puede quedar vacía"
                            + " (NAMESPACE_RESERVED_NAMES)");
        }
        if (namespaceGracePeriod == null || namespaceGracePeriod.isNegative()
                || namespaceGracePeriod.isZero()) {
            throw new IllegalArgumentException(
                    "cuenta.namespace-grace-period debe ser positiva (NAMESPACE_GRACE_PERIOD, p. ej. PT1H)");
        }
    }
}
