package io.github.spendice.linkshortener;

import io.github.spendice.linkshortener.config.CuentaProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La inactividad de la sesión se graba en segundos enteros: un valor que
 * trunque a 0 vence al instante y uno que desborde un {@code int} pierde el
 * vencimiento, así que se rechazan al construir la configuración.
 */
class CuentaPropertiesTest {

    @Test
    void aceptaDuracionesRepresentables() {
        assertThat(new CuentaProperties(Duration.ofDays(30)).mainSessionIdleTimeout())
                .isEqualTo(Duration.ofDays(30));
    }

    @Test
    void rechazaDuracionesQueNoLleganAUnSegundo() {
        assertThatThrownBy(() -> new CuentaProperties(Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entre 1 segundo");
    }

    @Test
    void rechazaDuracionesQueDesbordanLosSegundosEnteros() {
        assertThatThrownBy(() -> new CuentaProperties(Duration.ofDays(24_856)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entre 1 segundo");
    }
}
