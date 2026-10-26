package io.github.spendice.linkshortener;

import io.github.spendice.linkshortener.config.CuentaProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La inactividad de la sesión se graba en segundos enteros: un valor que
 * trunque a 0 vence al instante y uno que desborde un {@code int} pierde el
 * vencimiento, así que se rechazan al construir la configuración. Los
 * nombres reservados se normalizan a minúsculas y no pueden quedar vacíos.
 */
class CuentaPropertiesTest {

    private static final List<String> RESERVADOS = List.of("api", "login");

    @Test
    void aceptaDuracionesRepresentables() {
        assertThat(new CuentaProperties(Duration.ofDays(30), RESERVADOS)
                .mainSessionIdleTimeout())
                .isEqualTo(Duration.ofDays(30));
    }

    @Test
    void rechazaDuracionesQueNoLleganAUnSegundo() {
        assertThatThrownBy(() -> new CuentaProperties(Duration.ofMillis(500), RESERVADOS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entre 1 segundo");
    }

    @Test
    void rechazaDuracionesQueDesbordanLosSegundosEnteros() {
        assertThatThrownBy(() -> new CuentaProperties(Duration.ofDays(24_856), RESERVADOS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entre 1 segundo");
    }

    @Test
    void normalizaLosNombresReservadosAMinusculas() {
        CuentaProperties propiedades = new CuentaProperties(
                Duration.ofDays(30), List.of(" API ", "Login", "api", ""));

        assertThat(propiedades.namespaceReservedNames()).containsExactly("api", "login");
    }

    @Test
    void exigeAlMenosUnNombreReservado() {
        assertThatThrownBy(() -> new CuentaProperties(Duration.ofDays(30), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace-reserved-names");
    }
}
