package io.github.spendice.linkshortener;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La demora de la redirección automática del aviso es configurable
 * (issue #25): con otro {@code cuenta.namespace-notice-delay} la página
 * lo refleja en el meta refresh, el atributo y la cuenta regresiva.
 * Contexto propio por la propiedad; PostgreSQL y proveedor OIDC
 * simulados son los del fixture.
 */
@TestPropertySource(properties = "cuenta.namespace-notice-delay=PT12S")
class AvisoNamespaceDemoraConfiguradaHttpTest extends HttpApiFixture {

    @Test
    void laDemoraDelAvisoEsLaConfigurada() throws Exception {
        HttpClient juan = usuarioConNamespace(
                "aviso-demora", "aviso-demora@ejemplo.com", "juan");
        assertThat(postEnlacePersonal(juan, "https://ejemplo.com/doc", "mi-doc")
                .statusCode()).isEqualTo(201);
        assertThat(postNamespace(juan, "juani").statusCode()).isEqualTo(200);

        HttpResponse<String> aviso = get("/juan/mi-doc");

        assertThat(aviso.statusCode()).isEqualTo(200);
        assertThat(aviso.body())
                .contains("content=\"12;url=" + PUBLIC_BASE + "/juani/mi-doc\"")
                .contains("<span id=\"cuenta\">12</span>");
    }
}
