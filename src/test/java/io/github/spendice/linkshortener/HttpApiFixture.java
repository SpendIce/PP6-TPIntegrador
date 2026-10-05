package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture compartido de las pruebas HTTP: contexto Spring Boot en puerto
 * aleatorio con PostgreSQL real (Testcontainers, ADR 0004), cliente HTTP
 * que nunca sigue redirecciones (el tiempo de carga del destino externo
 * queda fuera de la verificación) y utilidades del contrato
 * ({@code POST /api/links}, {@code GET /{ruta}} y el formato de error
 * {@code {error, message}}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "shortener.public-base-url=http://192.168.50.10:8080",
        "shortener.reserved-routes=api, error"
})
abstract class HttpApiFixture {

    static final String PUBLIC_BASE = "http://192.168.50.10:8080";

    /**
     * Contenedor único para todas las suites del fixture (patrón
     * singleton): arranca una vez al cargar la clase y queda vivo hasta
     * el fin del proceso. No usa {@code @Container}/{@code @Testcontainers}
     * porque las subclases comparten el contexto Spring en caché y su
     * datasource: un ciclo de vida por clase reiniciaría la base con otro
     * puerto mientras el contexto sigue apuntando al primero.
     */
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.4-alpine");

    static {
        POSTGRES.start();
    }

    protected final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @LocalServerPort
    int port;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate db;

    @BeforeEach
    void espacioDeCodigosVacio() {
        // Un alias vencido es candidato a reciclaje en cualquier
        // creación: el espacio vacío mantiene aislados los escenarios de
        // todas las suites que comparten el contenedor.
        db.execute("TRUNCATE TABLE alias, asignacion RESTART IDENTITY");
        db.update("UPDATE generador_alias SET proximo_indice = 0");
    }

    HttpResponse<String> postJson(String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/links"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(jsonBody))
                .build();
        return client.send(request, BodyHandlers.ofString());
    }

    /** POST sin cuerpo ni Content-Type: el contrato lo rechaza. */
    HttpResponse<String> postSinCuerpo() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/links"))
                .POST(BodyPublishers.noBody())
                .build();
        return client.send(request, BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build();
        return client.send(request, BodyHandlers.ofString());
    }

    String baseUrl() {
        return "http://localhost:" + port;
    }

    /**
     * El contrato de error es {@code {error, message}}: código estable
     * para el cliente y texto legible para mostrar al usuario.
     */
    void assertError(HttpResponse<String> response, String expectedCode) throws Exception {
        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("error").asText()).isEqualTo(expectedCode);
        assertThat(body.get("message").asText()).isNotBlank();
    }
}
