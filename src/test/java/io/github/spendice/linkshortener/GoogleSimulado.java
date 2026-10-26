package io.github.spendice.linkshortener;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proveedor OIDC simulado ({@code mock-oauth2-server} en Testcontainers,
 * ADR 0007): reemplaza a Google en las suites que inician sesión de
 * verdad. El contenedor es único para toda la JVM, como el PostgreSQL
 * del fixture: arranca al cargar la clase y queda vivo hasta el fin del
 * proceso. La imagen standalone habilita el login interactivo por
 * defecto: {@code /authorize} sirve un formulario que recibe
 * {@code username} (el {@code sub} del token) y {@code claims}
 * (JSON opcional que se funde en el token).
 *
 * <p>Los contextos que inician sesión apuntan el issuer de Google al
 * mock con {@link #issuerUri()}; los que nunca entran solo necesitan las
 * credenciales de relleno {@link #CLIENT_ID}/{@link #CLIENT_SECRET}
 * (constantes que no arrancan el contenedor).</p>
 */
final class GoogleSimulado {

    static final String CLIENT_ID = "cliente-de-pruebas";
    static final String CLIENT_SECRET = "secreto-de-pruebas";

    private static final String ISSUER_ID = "default";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static final GenericContainer<?> OAUTH2 = new GenericContainer<>(
            DockerImageName.parse("ghcr.io/navikt/mock-oauth2-server:6.1.2"))
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/isalive").forStatusCode(200));

    static {
        OAUTH2.start();
    }

    private GoogleSimulado() {
    }

    /**
     * Issuer del mock como lo ve la aplicación (misma JVM): la
     * configuración por descubrimiento apunta acá igual que en producción
     * apunta a {@code accounts.google.com}.
     */
    static String issuerUri() {
        return "http://" + TestHost.host() + ":" + OAUTH2.getMappedPort(8080) + "/" + ISSUER_ID;
    }

    /**
     * Inicia una sesión principal recorriendo el flujo real del contrato:
     * la redirección de la web al proveedor, el envío del formulario del
     * mock con {@code sub} y {@code email}, la vuelta con el código (la
     * aplicación lo canjea y valida el token por JWKS) y la creación de
     * la sesión. {@code dispositivo} tiene que guardar cookies
     * ({@link HttpApiFixture#nuevoClienteDispositivo()}): la sesión de la
     * solicitud OAuth2 y la {@code SESSION} final quedan en su almacén.
     * Devuelve la respuesta de la vuelta (302 hacia la web).
     */
    static HttpResponse<String> login(HttpClient dispositivo, String baseUrl,
                                      String sub, String email) throws Exception {
        // El acceso de la web: la aplicación redirige al proveedor y crea
        // la sesión que guarda el estado del pedido OAuth2.
        URI autorizar = redireccion(dispositivo, baseUrl + "/oauth2/authorization/google");
        assertThat(autorizar.toString()).startsWith(issuerUri() + "/authorize");

        // El formulario del mock se publica a la misma URL con la query
        // del pedido original, como hace el navegador.
        HttpResponse<String> formulario = get(dispositivo, autorizar);
        assertThat(formulario.statusCode()).isEqualTo(200);
        String claims = "{\"email\":\"" + email + "\"}";
        String cuerpo = "username=" + URLEncoder.encode(sub, StandardCharsets.UTF_8)
                + "&claims=" + URLEncoder.encode(claims, StandardCharsets.UTF_8);
        HttpResponse<String> respuesta = enviar(dispositivo,
                HttpRequest.newBuilder(autorizar)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .timeout(TIMEOUT)
                        .POST(BodyPublishers.ofString(cuerpo))
                        .build());
        assertThat(respuesta.statusCode()).isEqualTo(302);
        URI vuelta = URI.create(respuesta.headers().firstValue("Location").orElseThrow());
        assertThat(vuelta.toString()).startsWith(baseUrl + "/login/oauth2/code/google");

        // La vuelta con el código: la aplicación lo canjea contra el mock,
        // valida el id_token y materializa la sesión principal.
        return enviar(dispositivo, HttpRequest.newBuilder(vuelta)
                .GET().timeout(TIMEOUT).build());
    }

    private static URI redireccion(HttpClient cliente, String url) throws Exception {
        HttpResponse<String> respuesta = enviar(cliente,
                HttpRequest.newBuilder(URI.create(url)).GET().timeout(TIMEOUT).build());
        assertThat(respuesta.statusCode()).isEqualTo(302);
        return URI.create(respuesta.headers().firstValue("Location").orElseThrow());
    }

    private static HttpResponse<String> get(HttpClient cliente, URI uri) throws Exception {
        return enviar(cliente, HttpRequest.newBuilder(uri).GET().timeout(TIMEOUT).build());
    }

    private static HttpResponse<String> enviar(HttpClient cliente, HttpRequest pedido)
            throws Exception {
        return cliente.send(pedido, BodyHandlers.ofString());
    }
}
