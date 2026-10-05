package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.spendice.linkshortener.application.port.AliasStore;
import io.github.spendice.linkshortener.application.port.AssignmentStore;
import io.github.spendice.linkshortener.domain.Alias;
import io.github.spendice.linkshortener.domain.Assignment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recorrido principal sobre la API HTTP con PostgreSQL real
 * (Testcontainers, ADR 0004): creación, respuesta del contrato,
 * persistencia y redirección. El cliente HTTP nunca sigue redirecciones,
 * como exige la medición acordada: el tiempo de carga del destino externo
 * queda fuera de la verificación.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "shortener.public-base-url=http://192.168.50.10:8080",
        "shortener.reserved-routes=api, error"
})
@Testcontainers
class LinkApiHttpTest {

    private static final String PUBLIC_BASE = "http://192.168.50.10:8080";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @LocalServerPort
    int port;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate db;
    @Autowired
    AliasStore aliasStore;
    @Autowired
    AssignmentStore assignmentStore;

    @BeforeEach
    void espacioDeCodigosVacio() {
        // Un alias vencido es candidato a reciclaje en cualquier
        // creación: el espacio vacío mantiene los escenarios aislados.
        db.execute("TRUNCATE TABLE alias, asignacion RESTART IDENTITY");
        db.update("UPDATE generador_alias SET proximo_indice = 0");
    }

    @Test
    void creaAsignacionYDevuelveEnlacePublicoConVencimiento() throws Exception {
        Instant before = Instant.now();

        HttpResponse<String> response = postJson("{\"destination\":\"https://docs.ejemplo.com/a?x=1#s\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        String alias = body.get("alias").asText();
        assertThat(alias).hasSize(1).matches("[1-9A-HJ-NP-Za-km-z]");
        assertThat(body.get("shortUrl").asText()).isEqualTo(PUBLIC_BASE + "/" + alias);
        Instant expiresAt = Instant.parse(body.get("expiresAt").asText());
        assertThat(expiresAt)
                .isAfter(before.plus(Duration.ofMinutes(59)))
                .isBefore(Instant.now().plus(Duration.ofMinutes(61)));
        assertThat(body.get("reuseNotice").asText()).contains("reutilizarse");

        List<Map<String, Object>> rows = db.queryForList(
                "SELECT a.destino, a.creada_en, a.vence_en, al.asignacion_actual_id, a.id"
                        + " FROM asignacion a JOIN alias al ON al.codigo = a.alias_codigo"
                        + " WHERE a.alias_codigo = ?", alias);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("destino")).isEqualTo("https://docs.ejemplo.com/a?x=1#s");
        assertThat(rows.get(0).get("asignacion_actual_id")).isEqualTo(rows.get(0).get("id"));
    }

    @Test
    void redirige302ConDestinoVerbatimYNoStore() throws Exception {
        String destination = "https://usuario:clave@ejemplo.com:8443/ruta%20x?a=1&b=2#frag";
        String alias = createAliasFor(destination);

        HttpResponse<String> redirect = get("/" + alias);

        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("Location")).contains(destination);
        assertThat(redirect.headers().firstValue("Cache-Control")).contains("no-store");
    }

    @Test
    void aliasDesconocidoDevuelve404ConMensajeAcordado() throws Exception {
        HttpResponse<String> response = get("/zz9");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("Este enlace no existe o venció");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }

    @Test
    void aliasVencidoDevuelveElMismo404() throws Exception {
        Instant now = Instant.now();
        Alias alias = aliasStore.claim(now);
        Assignment vencida = assignmentStore.save(Assignment.pending(
                alias.code(), "https://ejemplo.com/viejo",
                now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1))));
        aliasStore.assignCurrent(alias.code(), vencida.id());

        HttpResponse<String> response = get("/" + alias.code());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("Este enlace no existe o venció");
    }

    @Test
    void dosCreacionesDelMismoDestinoSonIndependientes() throws Exception {
        String destination = "https://ejemplo.com/repetido";

        JsonNode first = json.readTree(postJson("{\"destination\":\"" + destination + "\"}").body());
        JsonNode second = json.readTree(postJson("{\"destination\":\"" + destination + "\"}").body());

        assertThat(second.get("alias").asText()).isNotEqualTo(first.get("alias").asText());

        List<Long> ids = db.queryForList(
                "SELECT id FROM asignacion WHERE destino = ?", Long.class, destination);
        assertThat(ids).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void aceptaDestinoDe8192Caracteres() throws Exception {
        String destination = "https://ejemplo.com/" + "a".repeat(8192 - "https://ejemplo.com/".length());

        HttpResponse<String> response = postJson("{\"destination\":\"" + destination + "\"}");

        assertThat(response.statusCode()).isEqualTo(201);
    }

    @Test
    void rechazaEntradasFueraDelContrato() throws Exception {
        assertError(postJson("{\"destination\":\"\"}"), "EMPTY_DESTINATION");
        assertError(postJson("{}"), "EMPTY_DESTINATION");
        assertError(postJson("{\"destination\":\"ftp://ejemplo.com/a\"}"), "UNSUPPORTED_SCHEME");
        assertError(postJson("{\"destination\":\"https://ejemplo.com/con espacios\"}"),
                "MALFORMED_DESTINATION");
        assertError(postJson("{\"destination\":\"https://ejemplo.com/sección\"}"),
                "MALFORMED_DESTINATION");

        String demasiadoLargo = "https://ejemplo.com/" + "a".repeat(8193 - "https://ejemplo.com/".length());
        assertError(postJson("{\"destination\":\"" + demasiadoLargo + "\"}"), "DESTINATION_TOO_LONG");

        assertError(postJson("{\"destination\":\"" + PUBLIC_BASE + "/propio\"}"), "OWN_ORIGIN");
        assertError(postJson("{\"destination\":\"http://localhost:8080/propio\"}"), "OWN_ORIGIN");
        assertError(postJson("{\"destination\":\"http://127.0.0.1:8080/propio\"}"), "OWN_ORIGIN");

        assertError(postJson("esto no es json"), "INVALID_REQUEST");
    }

    @Test
    void creacionesConcurrentesReservanAliasExclusivos() throws Exception {
        int concurrent = 10;
        var pool = Executors.newFixedThreadPool(concurrent);
        var ready = new CountDownLatch(concurrent);
        var go = new CountDownLatch(1);
        var futures = new ArrayList<Future<HttpResponse<String>>>();
        for (int i = 0; i < concurrent; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return postJson("{\"destination\":\"https://ejemplo.com/concurrente\"}");
            }));
        }
        ready.await();
        go.countDown();

        Set<String> aliases = new HashSet<>();
        for (Future<HttpResponse<String>> future : futures) {
            HttpResponse<String> response = future.get();
            assertThat(response.statusCode()).isEqualTo(201);
            aliases.add(json.readTree(response.body()).get("alias").asText());
        }
        pool.shutdown();
        assertThat(aliases).hasSize(concurrent);
    }

    @Test
    void laWebSeSirveDesdeElBackend() throws Exception {
        HttpResponse<String> response = get("/");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/html");
        assertThat(response.body()).contains("Dirección a acortar");
    }

    @Test
    void laWebReferenciaYSirveLosRecursosDelQr() throws Exception {
        HttpResponse<String> page = get("/");

        assertThat(page.body())
                .contains("vendor/qrcode.js")
                .contains("qr-code.js")
                .contains("qr-image")
                .contains("qr-download");

        HttpResponse<String> lib = get("/vendor/qrcode.js");
        assertThat(lib.statusCode()).isEqualTo(200);
        assertThat(lib.body()).contains("Kazuhiko Arase");

        HttpResponse<String> module = get("/qr-code.js");
        assertThat(module.statusCode()).isEqualTo(200);
        assertThat(module.body()).contains("QrPng");
    }

    private String createAliasFor(String destination) throws Exception {
        JsonNode body = json.readTree(postJson("{\"destination\":\"" + destination + "\"}").body());
        return body.get("alias").asText();
    }

    private HttpResponse<String> postJson(String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/links"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(jsonBody))
                .build();
        return client.send(request, BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build();
        return client.send(request, BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private void assertError(HttpResponse<String> response, String expectedCode) throws Exception {
        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("error").asText()).isEqualTo(expectedCode);
    }
}
