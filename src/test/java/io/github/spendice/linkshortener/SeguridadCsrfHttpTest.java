package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regresión de la capa de seguridad del issue #18 sobre PostgreSQL real
 * (Testcontainers, ADR 0004): el contrato anónimo no cambia — la web, la
 * resolución, los estáticos y la creación siguen sin pedir
 * autenticación — y los POST exigen el token CSRF del doble envío
 * ({@code GET /api/csrf} + cookie {@code XSRF-TOKEN} + encabezado
 * {@code X-XSRF-TOKEN}). La infraestructura compartida está en
 * {@link HttpApiFixture}.
 */
class SeguridadCsrfHttpTest extends HttpApiFixture {

    @Test
    void postSinTokenCsrfDevuelve403ConElFormatoDeError() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/links"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"destination\":\"https://ejemplo.com/a\"}"))
                .build();

        HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

        assertRechazoCsrf(response);
    }

    @Test
    void postConEncabezadoPeroSinCookieDevuelve403() throws Exception {
        // El doble envío exige cookie y encabezado coincidentes: un
        // encabezado solo no alcanza, ni siquiera con un token emitido.
        String csrf = Csrf.obtener(client, baseUrl());
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/links"))
                .header("Content-Type", "application/json")
                .header(Csrf.ENCABEZADO, csrf)
                .POST(BodyPublishers.ofString("{\"destination\":\"https://ejemplo.com/a\"}"))
                .build();

        HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

        assertRechazoCsrf(response);
    }

    @Test
    void postConEncabezadoInventadoDevuelve403() throws Exception {
        // El doble envío acepta cualquier par cookie+encabezado que
        // coincida: el token no está firmado y el servidor no guarda
        // estado. Lo que un sitio atacante no puede hacer es leer la
        // cookie del servicio para repetirla en el encabezado, así que el
        // ataque real es una cookie emitida con un encabezado inventado.
        String csrf = Csrf.obtener(client, baseUrl());
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/links"))
                .header("Content-Type", "application/json")
                .header("Cookie", Csrf.COOKIE + "=" + csrf)
                .header(Csrf.ENCABEZADO, "token-inventado")
                .POST(BodyPublishers.ofString("{\"destination\":\"https://ejemplo.com/a\"}"))
                .build();

        HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

        assertRechazoCsrf(response);
    }

    @Test
    void unDispositivoConCookiesCreaEnlaceSinSesion() throws Exception {
        // El cliente-dispositivo del fixture guarda la cookie XSRF-TOKEN
        // de GET /api/csrf y la devuelve con el encabezado: el flujo
        // completo del POST anónimo no crea sesión de aplicación.
        HttpClient dispositivo = nuevoClienteDispositivo();

        // El GET /api/csrf es donde nacería una sesión si el repositorio del
        // token cambiara a uno con estado: se revisan sus Set-Cookie además
        // de los del POST.
        HttpResponse<String> csrf = get(dispositivo, "/api/csrf");
        assertThat(csrf.statusCode()).isEqualTo(200);
        assertThat(cookiesDeSesion(csrf)).isEmpty();

        HttpResponse<String> response = postJson(dispositivo, "{\"destination\":\"https://ejemplo.com/movil\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(cookiesDeSesion(response)).isEmpty();
    }

    /** Cookies de sesión de aplicación: {@code JSESSIONID} o cualquier {@code SESSION=}. */
    private static List<String> cookiesDeSesion(HttpResponse<String> response) {
        return response.headers().allValues("set-cookie").stream()
                .filter(cookie -> cookie.startsWith("JSESSIONID=") || cookie.startsWith("SESSION="))
                .toList();
    }

    @Test
    void losGetPublicosNoPidenAutenticacion() throws Exception {
        HttpClient dispositivo = nuevoClienteDispositivo();

        HttpResponse<String> web = get(dispositivo, "/");
        assertThat(web.statusCode()).isEqualTo(200);

        HttpResponse<String> estatico = get(dispositivo, "/styles.css");
        assertThat(estatico.statusCode()).isEqualTo(200);
        // Spring Security no debe imponer su `no-store` por defecto a los
        // estáticos: el navegador tiene que poder cachearlos como antes.
        assertThat(estatico.headers().allValues("cache-control"))
                .noneMatch(valor -> valor.contains("no-store"));

        HttpResponse<String> aliasDesconocido = get(dispositivo, "/zz9");
        assertThat(aliasDesconocido.statusCode()).isEqualTo(404);
        assertThat(aliasDesconocido.body()).contains("Este enlace no existe o venció");

        HttpResponse<String> reservada = get(dispositivo, "/api/ruta-que-no-existe");
        assertThat(reservada.statusCode()).isEqualTo(404);

        HttpResponse<String> csrf = get(dispositivo, "/api/csrf");
        assertThat(csrf.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(csrf.body());
        assertThat(body.get("token").asText()).isNotBlank();
    }

    /**
     * El rechazo CSRF del contrato: 403 con el formato de error
     * {@code {error, message}} y el código {@code CSRF_TOKEN_INVALID}
     * documentado en {@code openapi.yaml}.
     */
    private void assertRechazoCsrf(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("application/json");
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("error").asText()).isEqualTo("CSRF_TOKEN_INVALID");
        assertThat(body.get("message").asText()).isNotBlank();
    }
}
