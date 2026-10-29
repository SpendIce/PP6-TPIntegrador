package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los nombres reservados son configurables (issue #20): con una lista distinta
 * del default, el endpoint rechaza el nombre configurado y sigue aceptando uno
 * que el default no reservaba. Contexto propio por la propiedad; PostgreSQL y
 * proveedor OIDC simulados son los del fixture.
 */
@TestPropertySource(properties = "cuenta.namespace-reserved-names=api,login,personalizado")
class NamespaceReservadosConfiguradosHttpTest extends HttpApiFixture {

    @Test
    void rechazaElNombreReservadoPorConfiguracion() throws Exception {
        HttpClient juan = sesionDe("google-sub-reservado", "reservado@ejemplo.com");

        HttpResponse<String> rechazo = postJson(juan, "/api/namespace",
                "{\"namespace\":\"personalizado\"}");

        assertThat(rechazo.statusCode()).isEqualTo(400);
        JsonNode cuerpo = json.readTree(rechazo.body());
        assertThat(cuerpo.get("error").asText()).isEqualTo("NAMESPACE_RESERVED");
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM usuario WHERE namespace = 'personalizado'", Long.class))
                .isZero();
    }

    @Test
    void aceptaUnNombreQueElDefaultNoReservaba() throws Exception {
        // `docs` no está en la lista configurada para este contexto.
        HttpClient juan = sesionDe("google-sub-no-reservado", "noreservado@ejemplo.com");

        HttpResponse<String> alta = postJson(juan, "/api/namespace",
                "{\"namespace\":\"docs\"}");

        assertThat(alta.statusCode()).isEqualTo(200);
        assertThat(json.readTree(alta.body()).get("usuario").get("namespace").asText())
                .isEqualTo("docs");
    }
}
