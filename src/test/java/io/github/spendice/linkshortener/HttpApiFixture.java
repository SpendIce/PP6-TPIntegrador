package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

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
     * Tope por solicitud: una respuesta que tarda más que esto es un
     * fallo de la prueba, no un hilo que cuelga la suite.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /**
     * Contenedor único para todas las suites del fixture (patrón
     * singleton): arranca una vez al cargar la clase y queda vivo hasta
     * el fin del proceso. No usa {@code @Container}/{@code @Testcontainers}
     * porque las subclases comparten el contexto Spring en caché y su
     * datasource: un ciclo de vida por clase reiniciaría la base con otro
     * puerto mientras el contexto sigue apuntando al primero.
     */
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.4-alpine");

    static {
        POSTGRES.start();
    }

    /**
     * `@ServiceConnection` arma la URL con `localhost`, y en el sandbox de
     * stinter el puerto mapeado vive en el host: la conexión se rechaza y el
     * contexto no carga. El datasource se declara con el host de
     * {@link TestHost}, que es `localhost` en el host y el gateway del bridge
     * dentro de un contenedor.
     */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> TestHost.jdbcUrl(POSTGRES));
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Google se reemplaza por el proveedor OIDC simulado (ADR 0007): el
     * contexto descubre sus endpoints desde el issuer del contenedor, lo
     * mismo que en producción hace con {@code accounts.google.com}, así
     * que el contenedor arranca antes de que se cree el contexto. Las
     * suites del fixture lo comparten aunque no inicien sesión, como
     * comparten el PostgreSQL.
     */
    @DynamicPropertySource
    static void googleSimulado(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.google.issuer-uri",
                GoogleSimulado::issuerUri);
        registry.add("spring.security.oauth2.client.registration.google.client-id",
                () -> GoogleSimulado.CLIENT_ID);
        registry.add("spring.security.oauth2.client.registration.google.client-secret",
                () -> GoogleSimulado.CLIENT_SECRET);
    }

    protected final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * Un "dispositivo" del contrato: cliente con su propio almacén de
     * cookies, independiente del compartido de la suite. Es la base de
     * las pruebas de sesión de la etapa 2; la regresión anónima ya lo
     * usa para recorrer el flujo CSRF con cookies reales.
     */
    protected HttpClient nuevoClienteDispositivo() {
        return Dispositivos.nuevo();
    }

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
        // todas las suites que comparten el contenedor. Usuarios y
        // sesiones persistidas (issue #19) también se limpian.
        db.execute("TRUNCATE TABLE alias, asignacion, asignacion_personal, namespace_anterior,"
                + " nombre_namespace, usuario, codigo_vinculacion, spring_session,"
                + " spring_session_attributes RESTART IDENTITY");
        db.update("UPDATE generador_alias SET proximo_indice = 0");
    }

    HttpResponse<String> postJson(String jsonBody) throws Exception {
        return postJson(client, jsonBody);
    }

    /**
     * POST del contrato con su token CSRF (issue #18): el cliente obtiene
     * el token con {@code GET /api/csrf} — si guarda cookies, la cookie
     * queda en su propio almacén — y lo repite por encabezado y cookie.
     */
    HttpResponse<String> postJson(HttpClient cliente, String jsonBody) throws Exception {
        return postJson(cliente, "/api/links", jsonBody);
    }

    /** POST con cuerpo JSON y CSRF a cualquier ruta del contrato. */
    HttpResponse<String> postJson(HttpClient cliente, String path, String jsonBody)
            throws Exception {
        String csrf = Csrf.obtener(cliente, baseUrl());
        HttpRequest request = Csrf.aplicar(cliente,
                        HttpRequest.newBuilder(URI.create(baseUrl() + path)), csrf)
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(BodyPublishers.ofString(jsonBody))
                .build();
        return cliente.send(request, BodyHandlers.ofString());
    }

    /** POST sin cuerpo ni Content-Type: el contrato lo rechaza. */
    HttpResponse<String> postSinCuerpo() throws Exception {
        String csrf = Csrf.obtener(client, baseUrl());
        HttpRequest request = Csrf.aplicar(client,
                        HttpRequest.newBuilder(URI.create(baseUrl() + "/api/links")), csrf)
                .timeout(REQUEST_TIMEOUT)
                .POST(BodyPublishers.noBody())
                .build();
        return client.send(request, BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path) throws Exception {
        return get(client, path);
    }

    HttpResponse<String> get(HttpClient cliente, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .GET().timeout(REQUEST_TIMEOUT).build();
        return cliente.send(request, BodyHandlers.ofString());
    }

    String baseUrl() {
        return "http://localhost:" + port;
    }

    /**
     * El contrato de error es {@code {error, message}}: código estable
     * para el cliente y texto legible para mostrar al usuario.
     */
    void assertError(HttpResponse<String> response, String expectedCode) throws Exception {
        assertError(response, 400, expectedCode);
    }

    /** Igual que {@link #assertError(HttpResponse, String)} con estado explícito. */
    void assertError(HttpResponse<String> response, int expectedStatus, String expectedCode)
            throws Exception {
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("error").asText()).isEqualTo(expectedCode);
        assertThat(body.get("message").asText()).isNotBlank();
    }
}
