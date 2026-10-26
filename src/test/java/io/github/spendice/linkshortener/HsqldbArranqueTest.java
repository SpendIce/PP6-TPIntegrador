package io.github.spendice.linkshortener;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El perfil {@code hsqldb} (ADR 0005) tiene que seguir arrancando con la cadena
 * de seguridad de la etapa 2: Flyway deshabilitado, esquema desde
 * {@code db/hsqldb/schema.sql} y las rutas públicas respondiendo como antes.
 * Usa HSQLDB en memoria para no depender de Docker ni del servidor local.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:hsqldb:mem:arranque",
                "spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
        })
@ActiveProfiles("hsqldb")
class HsqldbArranqueTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @LocalServerPort
    int port;

    @Test
    void arrancaYRespondenLasRutasPublicas() throws Exception {
        assertThat(get("/").statusCode()).isEqualTo(200);

        HttpResponse<String> desconocido = get("/zz9");
        assertThat(desconocido.statusCode()).isEqualTo(404);
        assertThat(desconocido.body()).contains("Este enlace no existe o venció");

        HttpResponse<String> csrf = get("/api/csrf");
        assertThat(csrf.statusCode()).isEqualTo(200);
        assertThat(csrf.body()).contains("\"token\"");
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET().build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
