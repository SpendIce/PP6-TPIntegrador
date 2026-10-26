package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requisito de arranque del registro OIDC (issue #19):
 * {@code GOOGLE_CLIENT_ID} y {@code GOOGLE_CLIENT_SECRET} no tienen
 * default: su ausencia corta el arranque con un mensaje que nombra la
 * variable que falta, y declararlas en el {@code .env} las resuelve
 * (antecedente: {@link ArchivoEnvHttpTest}). Corre con HSQLDB en memoria
 * para no depender de Docker, como {@link HsqldbArranqueTest}.
 */
class ArranqueOidcGoogleTest {

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path directorio;

    @Test
    void sinCredencialesDeGoogleElArranqueCortaNombrandoLaVariable() {
        assertThatThrownBy(() -> startApp(directorio.resolve("sin-env")))
                .hasStackTraceContaining("GOOGLE_CLIENT_ID");
    }

    @Test
    void lasCredencialesDeclaradasEnElArchivoEnvResuelvenElRegistro() throws Exception {
        Path env = directorio.resolve(".env");
        Files.writeString(env,
                "GOOGLE_CLIENT_ID=cliente-desde-env\nGOOGLE_CLIENT_SECRET=secreto-desde-env\n");

        try (ConfigurableApplicationContext app = startApp(env)) {
            int puerto = ((WebServerApplicationContext) app).getWebServer().getPort();
            HttpResponse<String> respuesta = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + puerto + "/api/sesion"))
                            .GET().build(),
                    BodyHandlers.ofString());
            assertThat(respuesta.statusCode()).isEqualTo(200);
            JsonNode body = json.readTree(respuesta.body());
            assertThat(body.get("usuario").isNull()).isTrue();
        }
    }

    private ConfigurableApplicationContext startApp(Path env) {
        // HSQLDB en memoria con el perfil hsqldb: sin Flyway, el esquema
        // lo crea db/hsqldb/schema.sql (ADR 0005).
        return new SpringApplicationBuilder(LinkShortenerApplication.class)
                .profiles("hsqldb")
                .run(
                        "--ENV_FILE=" + env,
                        "--server.port=0",
                        "--spring.datasource.url=jdbc:hsqldb:mem:arranque-oidc",
                        "--spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver",
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--shortener.public-base-url=http://192.168.50.10:8080");
    }
}
