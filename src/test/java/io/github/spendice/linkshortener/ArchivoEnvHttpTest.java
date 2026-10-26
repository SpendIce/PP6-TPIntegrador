package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La configuración de despliegue se puede declarar en un archivo
 * {@code .env} ({@code spring.config.import} de {@code application.yml}):
 * sus claves reemplazan los defaults de las variables de entorno. Los
 * contextos se arrancan programáticamente, como en
 * {@link EvolucionDuracionHttpTest}, porque la ruta del archivo
 * ({@code ENV_FILE}) es un argumento de arranque distinto en cada caso.
 */
@Testcontainers
class ArchivoEnvHttpTest {

    private static final Instant T0 = Instant.parse("2026-10-05T12:00:00Z");
    private static final String DESTINO = "https://ejemplo.com/desde-env";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @TempDir
    Path directorio;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void laDuracionDelArchivoEnvReemplazaLaDuracionPorDefecto() throws Exception {
        Path env = directorio.resolve(".env");
        Files.writeString(env, "LINK_DURATION=PT40M\n");
        Instant venceEn = T0.plus(Duration.ofMinutes(40));

        try (ConfigurableApplicationContext app = startApp(env)) {
            MutableClock reloj = app.getBean(MutableClock.class);
            int puerto = port(app);
            reloj.set(T0);
            String alias = crearEnlace(puerto, venceEn);

            AsignacionesPersistidas.verificar(app.getBean(JdbcTemplate.class), alias, DESTINO, T0, venceEn);

            reloj.set(venceEn.minusMillis(1));
            assertThat(get(puerto, "/" + alias).statusCode()).isEqualTo(302);
            reloj.set(venceEn);
            assertThat(get(puerto, "/" + alias).statusCode()).isEqualTo(404);
        }
    }

    @Test
    void sinArchivoEnvRigeLaDuracionPorDefecto() throws Exception {
        try (ConfigurableApplicationContext app = startApp(directorio.resolve("no-existe.env"))) {
            app.getBean(MutableClock.class).set(T0);
            Instant venceEn = T0.plus(Duration.ofMinutes(60));
            String alias = crearEnlace(port(app), venceEn);

            AsignacionesPersistidas.verificar(app.getBean(JdbcTemplate.class), alias, DESTINO, T0, venceEn);
        }
    }

    private ConfigurableApplicationContext startApp(Path env) {
        return new SpringApplicationBuilder(LinkShortenerApplication.class)
                .sources(ClockTestConfiguration.class)
                .run(
                        "--ENV_FILE=" + env,
                        "--server.port=0",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--shortener.public-base-url=http://192.168.50.10:8080",
                        "--spring.security.oauth2.client.registration.google.client-id="
                                + GoogleSimulado.CLIENT_ID,
                        "--spring.security.oauth2.client.registration.google.client-secret="
                                + GoogleSimulado.CLIENT_SECRET);
    }

    private int port(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    private String crearEnlace(int port, Instant expiresAtEsperado) throws Exception {
        String csrf = Csrf.obtener(client, "http://localhost:" + port);
        HttpRequest request = Csrf.aplicar(client,
                        HttpRequest.newBuilder(
                                URI.create("http://localhost:" + port + "/api/links")), csrf)
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"destination\":\"" + DESTINO + "\"}"))
                .build();
        HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        assertThat(Instant.parse(body.get("expiresAt").asText())).isEqualTo(expiresAtEsperado);
        return body.get("alias").asText();
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return client.send(request, BodyHandlers.ofString());
    }
}
