package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.spendice.linkshortener.domain.AliasSequence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato de validación de destinos sobre la API HTTP con PostgreSQL
 * real (Testcontainers, ADR 0004): conservación verbatim, límites de
 * entrada, casos internacionales, orígenes propios equivalentes y la
 * garantía de que un rechazo no crea asignación ni consume un alias.
 * La infraestructura compartida está en {@link HttpApiFixture}.
 */
class DestinationValidationHttpTest extends HttpApiFixture {

    @Autowired
    AliasSequence sequence;

    @Test
    void conservaElDestinoVerbatimEnPersistenciaYRedireccion() throws Exception {
        String destination = "https://usuario:cl%40ve@ejemplo.com:8443/ruta%20x%2Fy"
                + "?a=1&a=2&b=dos%20dos#secci%C3%B3n-3";

        JsonNode created = create(destination);
        String alias = created.get("alias").asText();

        assertThat(db.queryForObject(
                "SELECT destino FROM asignacion WHERE alias_codigo = ?", String.class, alias))
                .isEqualTo(destination);

        HttpResponse<String> redirect = get("/" + alias);
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("Location")).contains(destination);
    }

    @Test
    void aceptaElLimiteDe8192YLoPersisteCompleto() throws Exception {
        String destination = "https://ejemplo.com/doc?x=" + "y".repeat(8192 - "https://ejemplo.com/doc?x=".length());
        assertThat(destination).hasSize(8192);

        JsonNode created = create(destination);
        String alias = created.get("alias").asText();

        assertThat(db.queryForObject(
                "SELECT destino FROM asignacion WHERE alias_codigo = ?", String.class, alias))
                .isEqualTo(destination);
    }

    @Test
    void aceptaDestinosLanCredencialesEInaccesibles() throws Exception {
        assertThat(postJson("{\"destination\":\"http://192.168.50.99:9000/carpeta/recurso\"}")
                .statusCode()).isEqualTo(201);
        assertThat(postJson("{\"destination\":\"http://usuario:clave@192.168.50.99/archivo\"}")
                .statusCode()).isEqualTo(201);
        assertThat(postJson("{\"destination\":\"http://10.255.255.1/recurso-que-no-responde\"}")
                .statusCode()).isEqualTo(201);
        assertThat(postJson("{\"destination\":\"https://nombre-que-no-resuelve.invalid/pagina\"}")
                .statusCode()).isEqualTo(201);
    }

    @Test
    void aceptaFormasInternacionalesCodificadas() throws Exception {
        assertThat(postJson("{\"destination\":\"https://xn--bcher-kva.ch/\"}")
                .statusCode()).isEqualTo(201);
        assertThat(postJson("{\"destination\":\"https://ejemplo.com/secci%C3%B3n?nombre=jos%C3%A9\"}")
                .statusCode()).isEqualTo(201);
    }

    @Test
    void aceptaOtroPuertoDelMismoHostQueNoEsOrigenPropio() throws Exception {
        assertThat(postJson("{\"destination\":\"http://192.168.50.10:8081/otro-servicio\"}")
                .statusCode()).isEqualTo(201);
        assertThat(postJson("{\"destination\":\"http://localhost:9090/otro-servicio\"}")
                .statusCode()).isEqualTo(201);
    }

    @Test
    void rechazaOrigenesPropiosYSusEquivalentes() throws Exception {
        assertError(postJson("{\"destination\":\"" + PUBLIC_BASE + "/propio\"}"), "OWN_ORIGIN");
        assertError(postJson("{\"destination\":\"https://192.168.50.10:8080/propio\"}"), "OWN_ORIGIN");
        assertError(postJson("{\"destination\":\"http://localhost:8080/propio\"}"), "OWN_ORIGIN");
        assertError(postJson("{\"destination\":\"http://LOCALHOST.:8080/propio\"}"), "OWN_ORIGIN");
        assertError(postJson("{\"destination\":\"http://127.0.0.1:8080/propio\"}"), "OWN_ORIGIN");
        assertError(postJson("{\"destination\":\"http://[::1]:8080/propio\"}"), "OWN_ORIGIN");
        assertError(postJson("{\"destination\":\"http://usuario:clave@localhost:8080/propio\"}"),
                "OWN_ORIGIN");
    }

    @Test
    void rechazaCaracteresInternacionalesCrudosYEscapesInvalidos() throws Exception {
        assertError(postJson("{\"destination\":\"https://ejemplo.com/sección\"}"),
                "MALFORMED_DESTINATION");
        assertError(postJson("{\"destination\":\"https://bücher.ch/\"}"),
                "MALFORMED_DESTINATION");
        assertError(postJson("{\"destination\":\"https://ejemplo.com/%zz\"}"),
                "MALFORMED_DESTINATION");
        assertError(postJson("{\"destination\":\"https://ejemplo.com/colgado%\"}"),
                "MALFORMED_DESTINATION");
    }

    @Test
    void rechazaPropiedadesDesconocidasDelContrato() throws Exception {
        assertError(postJson("{\"destination\":\"https://ejemplo.com/a\",\"extra\":1}"),
                "INVALID_REQUEST");
    }

    @Test
    void unRechazoNoCreaAsignacionNiConsumeAlias() throws Exception {
        long indiceAntes = proximoIndice();
        long aliasAntes = countRows("alias");
        long asignacionesAntes = countRows("asignacion");
        String aliasEsperado = sequence.codeAt(sequence.indexOfNextCode(indiceAntes));

        assertError(postJson("{\"destination\":\"https://ejemplo.com/con espacios\"}"),
                "MALFORMED_DESTINATION");

        assertThat(proximoIndice()).isEqualTo(indiceAntes);
        assertThat(countRows("alias")).isEqualTo(aliasAntes);
        assertThat(countRows("asignacion")).isEqualTo(asignacionesAntes);

        JsonNode created = create("https://ejemplo.com/tras-el-rechazo");
        assertThat(created.get("alias").asText()).isEqualTo(aliasEsperado);
    }

    @Test
    void unDestinoDe8193SeRechazaSinPersistirNiConsumirAlias() throws Exception {
        String destination = "https://ejemplo.com/" + "a".repeat(8193 - "https://ejemplo.com/".length());
        long indiceAntes = proximoIndice();
        long asignacionesAntes = countRows("asignacion");

        assertError(postJson("{\"destination\":\"" + destination + "\"}"), "DESTINATION_TOO_LONG");

        assertThat(proximoIndice()).isEqualTo(indiceAntes);
        assertThat(countRows("asignacion")).isEqualTo(asignacionesAntes);
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM asignacion WHERE destino = ?", Long.class, destination))
                .isZero();
    }

    @Test
    void laWebEntregaLaEntradaSinFiltrosPreviosDelCliente() throws Exception {
        HttpResponse<String> response = get("/");

        assertThat(response.statusCode()).isEqualTo(200);
        // La validación vive en la API: el formulario no debe truncar la
        // entrada (maxlength) ni impedir su envío (novalidate, sin type=url).
        assertThat(response.body()).contains("novalidate");
        assertThat(response.body()).doesNotContain("maxlength");
        assertThat(response.body()).doesNotContain("type=\"url\"");
    }

    private JsonNode create(String destination) throws Exception {
        HttpResponse<String> response = postJson("{\"destination\":\"" + destination + "\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body());
    }

    private long proximoIndice() {
        return db.queryForObject(
                "SELECT proximo_indice FROM generador_alias WHERE id = 1", Long.class);
    }

    private long countRows(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
