package io.github.spendice.linkshortener.domain;

import io.github.spendice.linkshortener.domain.InvalidDestinationException.Reason;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DestinationValidatorTest {

    private final DestinationValidator validator = new DestinationValidator(Set.of(
            new ServiceOrigin("localhost", 8080),
            new ServiceOrigin("127.0.0.1", 8080),
            new ServiceOrigin("::1", 8080),
            new ServiceOrigin("192.168.1.50", 8080)));

    @Nested
    class DestinosValidos {

        @Test
        void aceptaHttpYHttps() {
            assertThatCode(() -> validator.validate("http://docs.ejemplo.com/carpeta")).doesNotThrowAnyException();
            assertThatCode(() -> validator.validate("https://docs.ejemplo.com/carpeta")).doesNotThrowAnyException();
        }

        @Test
        void aceptaDestinosDeOtraMaquinaEnLaRedLocal() {
            assertThatCode(() -> validator.validate("http://192.168.1.99:9000/recurso")).doesNotThrowAnyException();
        }

        @Test
        void aceptaCredencialesIncrustadas() {
            assertThatCode(() -> validator.validate("http://usuario:clave@ejemplo.com/privado")).doesNotThrowAnyException();
        }

        @Test
        void conservaParametrosFragmentoYCodificacion() {
            String destino = "https://ejemplo.com/ruta%20con%20espacios?a=1&b=dos#seccion-3";
            assertThatCode(() -> validator.validate(destino)).doesNotThrowAnyException();
        }

        @Test
        void aceptaElLimiteDe8192Caracteres() {
            String destino = "https://ejemplo.com/" + "a".repeat(8192 - "https://ejemplo.com/".length());
            assertThat(destino).hasSize(8192);
            assertThatCode(() -> validator.validate(destino)).doesNotThrowAnyException();
        }

        @Test
        void aceptaDestinoInaccesibleSinConsultarDisponibilidad() {
            assertThatCode(() -> validator.validate("http://10.255.255.1/recurso-que-no-responde"))
                    .doesNotThrowAnyException();
        }

        @Test
        void aceptaDominioPunycodeYUtf8ConPercentEncoding() {
            assertThatCode(() -> validator.validate("https://xn--bcher-kva.ch/secci%C3%B3n?nombre=jos%C3%A9"))
                    .doesNotThrowAnyException();
        }

        @Test
        void aceptaEscapesEnMinusculasSinNormalizarlos() {
            assertThatCode(() -> validator.validate("http://ejemplo.com/%2f%2F%41"))
                    .doesNotThrowAnyException();
        }

        @Test
        void aceptaEsquemaEnMayusculas() {
            assertThatCode(() -> validator.validate("HTTPS://ejemplo.com/recurso"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class Rechazos {

        @Test
        void rechazaDestinoVacioOAusente() {
            assertThatThrownBy(() -> validator.validate(null))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.EMPTY_DESTINATION);
            assertThatThrownBy(() -> validator.validate("   "))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.EMPTY_DESTINATION);
        }

        @Test
        void rechazaFormatoInvalido() {
            assertThatThrownBy(() -> validator.validate("no-es-una-url"))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.UNSUPPORTED_SCHEME);
            assertThatThrownBy(() -> validator.validate("http://"))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.MALFORMED_DESTINATION);
            assertThatThrownBy(() -> validator.validate("http:///sin-host"))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.MALFORMED_DESTINATION);
        }

        @Test
        void rechazaEsquemasQueNoSonHttpNiHttps() {
            assertThatThrownBy(() -> validator.validate("ftp://ejemplo.com/archivo"))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.UNSUPPORTED_SCHEME);
            assertThatThrownBy(() -> validator.validate("mailto:alguien@ejemplo.com"))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.UNSUPPORTED_SCHEME);
        }

        @Test
        void rechazaEspaciosSinCodificar() {
            assertThatThrownBy(() -> validator.validate("https://ejemplo.com/ruta con espacios"))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.MALFORMED_DESTINATION);
            assertThatThrownBy(() -> validator.validate("https://ejemplo.com/a#frag con espacio"))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.MALFORMED_DESTINATION);
            assertThatThrownBy(() -> validator.validate(" https://ejemplo.com/con-espacio-inicial"))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.MALFORMED_DESTINATION);
        }

        @Test
        void rechazaEscapesDePorcentajeInvalidos() {
            assertThatThrownBy(() -> validator.validate("https://ejemplo.com/%zz"))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.MALFORMED_DESTINATION);
            assertThatThrownBy(() -> validator.validate("https://ejemplo.com/%2"))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.MALFORMED_DESTINATION);
            assertThatThrownBy(() -> validator.validate("https://ejemplo.com/colgado%"))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.MALFORMED_DESTINATION);
        }

        @Test
        void rechazaHostAusenteOSinAutoridad() {
            assertThatThrownBy(() -> validator.validate("http://user@/ruta"))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.MALFORMED_DESTINATION);
            assertThatThrownBy(() -> validator.validate("//ejemplo.com/ruta"))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.UNSUPPORTED_SCHEME);
        }

        @Test
        void rechazaMasDe8192CaracteresSinTruncar() {
            String destino = "https://ejemplo.com/" + "a".repeat(8193 - "https://ejemplo.com/".length());
            assertThat(destino).hasSize(8193);
            assertThatThrownBy(() -> validator.validate(destino))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.DESTINATION_TOO_LONG);
        }

        @Test
        void rechazaCaracteresInternacionalesSinCodificar() {
            assertThatThrownBy(() -> validator.validate("https://ejemplo.com/sección"))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.MALFORMED_DESTINATION);
        }

        @Test
        void rechazaOrigenesPropiosYEquivalentes() {
            assertThatThrownBy(() -> validator.validate("http://localhost:8080/abc"))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.OWN_ORIGIN);
            assertThatThrownBy(() -> validator.validate("http://127.0.0.1:8080/abc"))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.OWN_ORIGIN);
            assertThatThrownBy(() -> validator.validate("http://192.168.1.50:8080/abc"))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.OWN_ORIGIN);
        }

        @Test
        void rechazaOrigenesPropiosConVariantesDeEscritura() {
            assertThatThrownBy(() -> validator.validate("https://192.168.1.50:8080/abc"))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.OWN_ORIGIN);
            assertThatThrownBy(() -> validator.validate("http://LOCALHOST:8080/abc"))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.OWN_ORIGIN);
            assertThatThrownBy(() -> validator.validate("http://localhost.:8080/abc"))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.OWN_ORIGIN);
            assertThatThrownBy(() -> validator.validate("http://[::1]:8080/abc"))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.OWN_ORIGIN);
            assertThatThrownBy(() -> validator.validate("http://usuario:clave@localhost:8080/abc"))
                    .isInstanceOf(InvalidDestinationException.class)
                    .extracting("reason").isEqualTo(Reason.OWN_ORIGIN);
        }

        @Test
        void noRechazaOtroPuertoDelMismoHost() {
            assertThatCode(() -> validator.validate("http://localhost:9090/otro-servicio"))
                    .doesNotThrowAnyException();
        }
    }
}
