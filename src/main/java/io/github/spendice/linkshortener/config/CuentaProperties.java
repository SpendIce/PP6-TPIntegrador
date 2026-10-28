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
 *   <li>{@code linked-session-idle-timeout}: inactividad máxima que se
 *   graba en cada sesión vinculada al abrirla por la vinculación por QR
 *   ({@code LINKED_SESSION_IDLE_TIMEOUT}, {@code PT12H} por defecto).</li>
 *   <li>{@code link-code-ttl}: vigencia de cada código de vinculación
 *   ({@code LINK_CODE_TTL}, {@code PT60S} por defecto).</li>
 *   <li>{@code namespace-reserved-names}: nombres que ningún usuario
 *   puede elegir como namespace porque chocan con rutas del sistema
 *   ({@code NAMESPACE_RESERVED_NAMES}, {@code api,login,logout,vincular,oauth2,error}
 *   por defecto).</li>
 *   <li>{@code namespace-grace-period}: tiempo que un namespace anterior
 *   queda reservado para su dueño después de un cambio
 *   ({@code NAMESPACE_GRACE_PERIOD}, {@code PT1H} por defecto). Debe ser
 *   positivo; el fin se calcula al cambiar y modificar esta configuración
 *   no altera las gracias en curso.</li>
 *   <li>{@code namespace-notice-delay}: demora de la redirección
 *   automática de la página de aviso que sirve la dirección anterior
 *   durante la gracia ({@code NAMESPACE_NOTICE_DELAY}, {@code PT5S} por
 *   defecto).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "cuenta")
public record CuentaProperties(Duration mainSessionIdleTimeout,
                               List<String> namespaceReservedNames,
                               Duration namespaceGracePeriod,
                               Duration linkedSessionIdleTimeout,
                               Duration linkCodeTtl,
                               Duration namespaceNoticeDelay) {

    /**
     * TTL mínimo del código: el cliente web consulta el estado cada 2 s y
     * agenda la renovación con un piso de una consulta más 500 ms de margen
     * ({@code vinculacion-timers.js}), así que un código tiene que vivir
     * más que ese piso o la primera consulta nunca corre y ninguna
     * aprobación llega a materializarse. Cinco segundos dejan dos ciclos
     * completos aunque la respuesta tarde en llegar.
     */
    private static final Duration TTL_MINIMO = Duration.ofSeconds(5);

    public CuentaProperties {
        mainSessionIdleTimeout = validarDuracion(
                "cuenta.main-session-idle-timeout", "MAIN_SESSION_IDLE_TIMEOUT",
                mainSessionIdleTimeout, "P30D");
        linkedSessionIdleTimeout = validarDuracion(
                "cuenta.linked-session-idle-timeout", "LINKED_SESSION_IDLE_TIMEOUT",
                linkedSessionIdleTimeout, "PT12H");
        linkCodeTtl = validarDuracion(
                "cuenta.link-code-ttl", "LINK_CODE_TTL",
                linkCodeTtl, "PT60S");
        // Con un TTL debajo del mínimo el código vence antes de que la web
        // pueda consultar su estado una vez: se rechaza en el arranque en
        // vez de dejar una pantalla que nunca completa la vinculación.
        if (linkCodeTtl.compareTo(TTL_MINIMO) < 0) {
            throw new IllegalArgumentException(
                    "cuenta.link-code-ttl debe durar al menos " + TTL_MINIMO.getSeconds()
                            + " segundos (el cliente consulta el estado cada 2 s)"
                            + " y se recibió " + linkCodeTtl);
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
        // La página de aviso embebe la demora en segundos enteros (meta
        // refresh y cuenta regresiva): fuera de ese rango no se puede
        // servir una demora válida.
        namespaceNoticeDelay = validarDuracion(
                "cuenta.namespace-notice-delay", "NAMESPACE_NOTICE_DELAY",
                namespaceNoticeDelay, "PT5S");
    }

    /**
     * Las duraciones del área se graban en segundos enteros (la sesión en
     * {@code max_inactive_interval}; el código compara instantes): un
     * valor que trunque a 0 vence al instante y uno que desborde un
     * {@code int} pierde el vencimiento. Se rechaza en el arranque en vez
     * de corromper la sesión o el código.
     */
    private static Duration validarDuracion(String clave, String variable,
                                            Duration valor, String ejemplo) {
        if (valor == null) {
            throw new IllegalArgumentException(
                    clave + " es obligatoria (" + variable + ", p. ej. " + ejemplo + ")");
        }
        if (valor.compareTo(Duration.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    clave + " debe ser positiva y se recibió " + valor);
        }
        long segundos = valor.getSeconds();
        if (segundos < 1 || segundos > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    clave + " debe durar entre 1 segundo y " + Integer.MAX_VALUE
                            + " segundos y se recibió " + valor);
        }
        return valor;
    }
}
