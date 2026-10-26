package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sesión principal tras un reinicio del servicio (issue #19): la sesión
 * persistida en PostgreSQL con Spring Session JDBC sobrevive al proceso
 * que la creó. Dos contextos sucesivos contra la MISMA base, siguiendo
 * el antecedente de {@link ReinicioServicioHttpTest}: el primero inicia
 * sesión con Google (proveedor OIDC simulado, ADR 0007) y el segundo
 * reconoce la cookie {@code SESSION} sin volver a pedir credenciales.
 */
@Testcontainers
class SesionReinicioHttpTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    private final HttpClient dispositivo = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void laSesionPrincipalSigueValidaDespuesDeReiniciarElServicio() throws Exception {
        String sub = "google-sub-reinicio";
        String email = "reinicio@ejemplo.com";

        ConfigurableApplicationContext primera = startApp();
        try {
            int puerto = port(primera);
            HttpResponse<String> vuelta = GoogleSimulado.login(dispositivo, baseUrl(puerto), sub, email);
            assertThat(vuelta.statusCode()).isEqualTo(302);
            JsonNode sesion = json.readTree(get(puerto, "/api/sesion").body());
            assertThat(sesion.get("usuario").get("email").asText()).isEqualTo(email);
        } finally {
            primera.close();
        }

        // El segundo contexto levanta sobre la misma base: la cookie del
        // dispositivo sigue identificando la sesión persistida.
        ConfigurableApplicationContext reiniciada = startApp();
        try {
            JsonNode sesion = json.readTree(get(port(reiniciada), "/api/sesion").body());
            JsonNode usuario = sesion.get("usuario");
            assertThat(usuario.get("email").asText()).isEqualTo(email);
            assertThat(usuario.get("namespace").asText()).isEmpty();
            assertThat(usuario.get("tipo").asText()).isEqualTo("principal");
        } finally {
            reiniciada.close();
        }
    }

    private ConfigurableApplicationContext startApp() {
        // Las propiedades viajan como argumentos: tienen precedencia sobre
        // los defaults de application.yml. El issuer apunta al proveedor
        // OIDC simulado en lugar de Google (ADR 0007).
        return new SpringApplicationBuilder(LinkShortenerApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + TestHost.jdbcUrl(POSTGRES),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--shortener.public-base-url=http://192.168.50.10:8080",
                        "--spring.security.oauth2.client.provider.google.issuer-uri="
                                + GoogleSimulado.issuerUri(),
                        "--spring.security.oauth2.client.registration.google.client-id="
                                + GoogleSimulado.CLIENT_ID,
                        "--spring.security.oauth2.client.registration.google.client-secret="
                                + GoogleSimulado.CLIENT_SECRET);
    }

    private int port(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl(port) + path)).GET().build();
        return dispositivo.send(request, BodyHandlers.ofString());
    }

    private String baseUrl(int port) {
        return "http://localhost:" + port;
    }
}
