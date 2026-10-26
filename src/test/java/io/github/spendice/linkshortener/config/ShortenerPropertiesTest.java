package io.github.spendice.linkshortener.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Validaciones de arranque de la configuración de despliegue: los
 * defaults viven solo en {@code application.yml}, así que un valor
 * ausente, vacío o fuera de rango corta el arranque en lugar de caer a
 * otro valor.
 */
class ShortenerPropertiesTest {

    private static final URI BASE = URI.create("http://localhost:8080");
    private static final String ALFABETO = "123456789ABC";
    private static final String PATRON = "[1-9A-C]{1,32}";

    @Test
    void aceptaUnaDuracionDeExactamenteUnMinuto() {
        assertThat(conDuracion(Duration.ofMinutes(1)).linkDuration()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void rechazaUnaDuracionMenorAUnMinuto() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> conDuracion(Duration.ofSeconds(59)))
                .withMessageContaining("al menos un minuto");
    }

    @Test
    void rechazaUnNumeroSinUnidadQueSpringLeeComoMilisegundos() {
        // LINK_DURATION=40 llega al binder como 40 ms.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> conDuracion(Duration.ofMillis(40)))
                .withMessageContaining("PT40M");
    }

    @Test
    void rechazaUnaDuracionAusente() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> conDuracion(null))
                .withMessageContaining("link-duration es obligatoria");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void rechazaUnAlfabetoAusenteOVacio(String alfabeto) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ShortenerProperties(BASE, Duration.ofMinutes(60),
                        List.of(), List.of(), alfabeto, 8192, PATRON))
                .withMessageContaining("alias-alphabet es obligatorio");
    }

    @Test
    void rechazaUnAlfabetoConSimbolosRepetidos() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ShortenerProperties(BASE, Duration.ofMinutes(60),
                        List.of(), List.of(), "1123", 8192, PATRON))
                .withMessageContaining("repetidos");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rechazaUnLargoMaximoNoPositivo(int largo) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ShortenerProperties(BASE, Duration.ofMinutes(60),
                        List.of(), List.of(), ALFABETO, largo, PATRON))
                .withMessageContaining("debe ser positivo");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void rechazaUnPatronDeRutaAusenteOVacio(String patron) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ShortenerProperties(BASE, Duration.ofMinutes(60),
                        List.of(), List.of(), ALFABETO, 8192, patron))
                .withMessageContaining("alias-route-pattern es obligatorio");
    }

    private static ShortenerProperties conDuracion(Duration duracion) {
        return new ShortenerProperties(BASE, duracion, List.of(), List.of(), ALFABETO, 8192, PATRON);
    }
}
