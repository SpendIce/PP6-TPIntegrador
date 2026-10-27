package io.github.spendice.linkshortener;

import io.github.spendice.linkshortener.config.CuentaProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La inactividad de las sesiones y la vigencia del código de vinculación
 * se graban en segundos enteros: un valor que trunque a 0 vence al
 * instante y uno que desborde un {@code int} pierde el vencimiento, así
 * que se rechazan al construir la configuración. Los nombres reservados
 * se normalizan a minúsculas y no pueden quedar vacíos.
 */
class CuentaPropertiesTest {

    private static final List<String> RESERVADOS = List.of("api", "login");
    private static final Duration SESION_PRINCIPAL = Duration.ofDays(30);
    private static final Duration SESION_VINCULADA = Duration.ofHours(12);
    private static final Duration CODIGO = Duration.ofSeconds(60);

    private static CuentaProperties propiedades(Duration principal, List<String> reservados,
                                                Duration vinculada, Duration codigo) {
        return new CuentaProperties(principal, reservados, vinculada, codigo);
    }

    @Test
    void aceptaDuracionesRepresentables() {
        CuentaProperties propiedades =
                propiedades(SESION_PRINCIPAL, RESERVADOS, SESION_VINCULADA, CODIGO);

        assertThat(propiedades.mainSessionIdleTimeout()).isEqualTo(SESION_PRINCIPAL);
        assertThat(propiedades.linkedSessionIdleTimeout()).isEqualTo(SESION_VINCULADA);
        assertThat(propiedades.linkCodeTtl()).isEqualTo(CODIGO);
    }

    @Test
    void rechazaUnTtlDeCodigoQueNoDejaConsultarSuEstado() {
        // La web consulta cada 2 s con un piso de renovación de 2,5 s: un
        // código que vence antes nunca llega a consultarse.
        assertThatThrownBy(() -> propiedades(SESION_PRINCIPAL, RESERVADOS,
                SESION_VINCULADA, Duration.ofSeconds(4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("link-code-ttl")
                .hasMessageContaining("al menos 5 segundos");

        // En el mínimo admitido la configuración construye.
        assertThat(propiedades(SESION_PRINCIPAL, RESERVADOS, SESION_VINCULADA,
                Duration.ofSeconds(5)).linkCodeTtl()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void rechazaDuracionesQueNoLleganAUnSegundo() {
        assertThatThrownBy(() -> propiedades(Duration.ofMillis(500), RESERVADOS,
                SESION_VINCULADA, CODIGO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entre 1 segundo");
        assertThatThrownBy(() -> propiedades(SESION_PRINCIPAL, RESERVADOS,
                Duration.ofMillis(500), CODIGO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entre 1 segundo");
        assertThatThrownBy(() -> propiedades(SESION_PRINCIPAL, RESERVADOS,
                SESION_VINCULADA, Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entre 1 segundo");
    }

    @Test
    void rechazaDuracionesQueDesbordanLosSegundosEnteros() {
        Duration desborda = Duration.ofDays(24_856);

        assertThatThrownBy(() -> propiedades(desborda, RESERVADOS,
                SESION_VINCULADA, CODIGO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entre 1 segundo");
        assertThatThrownBy(() -> propiedades(SESION_PRINCIPAL, RESERVADOS,
                desborda, CODIGO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entre 1 segundo");
        assertThatThrownBy(() -> propiedades(SESION_PRINCIPAL, RESERVADOS,
                SESION_VINCULADA, desborda))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entre 1 segundo");
    }

    @Test
    void lasDuracionesSonObligatorias() {
        assertThatThrownBy(() -> propiedades(null, RESERVADOS, SESION_VINCULADA, CODIGO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("main-session-idle-timeout");
        assertThatThrownBy(() -> propiedades(SESION_PRINCIPAL, RESERVADOS, null, CODIGO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("linked-session-idle-timeout");
        assertThatThrownBy(() -> propiedades(SESION_PRINCIPAL, RESERVADOS, SESION_VINCULADA, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("link-code-ttl");
    }

    @Test
    void normalizaLosNombresReservadosAMinusculas() {
        CuentaProperties propiedades = propiedades(SESION_PRINCIPAL,
                List.of(" API ", "Login", "api", ""), SESION_VINCULADA, CODIGO);

        assertThat(propiedades.namespaceReservedNames()).containsExactly("api", "login");
    }

    @Test
    void exigeAlMenosUnNombreReservado() {
        assertThatThrownBy(() -> propiedades(SESION_PRINCIPAL, List.of(),
                SESION_VINCULADA, CODIGO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace-reserved-names");
    }
}
