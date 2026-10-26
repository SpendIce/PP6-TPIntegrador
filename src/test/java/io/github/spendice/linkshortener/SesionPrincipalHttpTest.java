package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sesión principal del issue #19 sobre PostgreSQL real (Testcontainers,
 * ADR 0004) con Google reemplazado por el proveedor OIDC simulado
 * ({@link GoogleSimulado}, ADR 0007): el primer login crea el usuario
 * identificado por el {@code sub} y registra en la sesión su tipo
 * {@code principal} y la inactividad máxima configurada, un segundo
 * login de la misma identidad reutiliza el usuario, {@code GET
 * /api/sesion} responde el contrato, {@code POST /api/logout} invalida
 * solo la sesión del dispositivo y el flujo anónimo de creación sigue
 * intacto. La infraestructura compartida está en {@link HttpApiFixture}.
 */
class SesionPrincipalHttpTest extends HttpApiFixture {

    private static final String SUB_JUAN = "google-sub-juan";
    private static final String EMAIL_JUAN = "juan@ejemplo.com";

    @Test
    void sinSesionRespondeQueNoHayUsuario() throws Exception {
        HttpClient dispositivo = nuevoClienteDispositivo();

        HttpResponse<String> respuesta = get(dispositivo, "/api/sesion");

        assertThat(respuesta.statusCode()).isEqualTo(200);
        assertThat(respuesta.headers().allValues("cache-control"))
                .anyMatch(valor -> valor.contains("no-store"));
        JsonNode body = json.readTree(respuesta.body());
        assertThat(body.get("usuario").isNull()).isTrue();
        // Consultar la sesión sin sesión no crea una: nada que persista
        // para el flujo anónimo.
        assertThat(respuesta.headers().allValues("set-cookie"))
                .noneMatch(cookie -> cookie.startsWith("SESSION="));
    }

    @Test
    void elPrimerLoginCreaElUsuarioYLaSesionPrincipal() throws Exception {
        HttpClient dispositivo = nuevoClienteDispositivo();

        HttpResponse<String> vuelta = GoogleSimulado.login(dispositivo, baseUrl(),
                SUB_JUAN, EMAIL_JUAN);

        // El login termina en la web, con la sesión persistida.
        assertThat(vuelta.statusCode()).isEqualTo(302);
        assertThat(vuelta.headers().firstValue("Location").orElse("")).endsWith("/");
        assertThat(cookieDeSesion(dispositivo)).isNotNull();

        List<Map<String, Object>> usuarios = db.queryForList("SELECT * FROM usuario");
        assertThat(usuarios).hasSize(1);
        assertThat(usuarios.get(0).get("google_sub")).isEqualTo(SUB_JUAN);
        assertThat(usuarios.get(0).get("email")).isEqualTo(EMAIL_JUAN);
        assertThat(usuarios.get(0).get("namespace")).isNull();

        assertSesionDelContrato(dispositivo, EMAIL_JUAN);
    }

    @Test
    void laSesionRegistraTipoPrincipalYLaInactividadMaximaConfigurada() throws Exception {
        HttpClient dispositivo = nuevoClienteDispositivo();
        GoogleSimulado.login(dispositivo, baseUrl(), SUB_JUAN, EMAIL_JUAN);

        // La inactividad máxima se lee de la tabla de Spring Session:
        // P30D del default son 2592000 segundos.
        String sessionId = cookieDeSesion(dispositivo);
        Integer inactividad = db.queryForObject(
                "SELECT max_inactive_interval FROM spring_session WHERE session_id = ?",
                Integer.class, sessionId);
        assertThat(inactividad).isEqualTo((int) Duration.ofDays(30).toSeconds());

        // La sesión registra su tipo y el usuario como atributos
        // persistidos.
        List<String> atributos = db.queryForList(
                "SELECT attribute_name FROM spring_session_attributes"
                        + " WHERE session_primary_id = (SELECT primary_id FROM spring_session"
                        + " WHERE session_id = ?)",
                String.class, sessionId);
        assertThat(atributos).contains("sesionTipo", "usuarioId");
    }

    @Test
    void unSegundoLoginDeLaMismaIdentidadReutilizaElUsuario() throws Exception {
        HttpClient primerDispositivo = nuevoClienteDispositivo();
        GoogleSimulado.login(primerDispositivo, baseUrl(), SUB_JUAN, EMAIL_JUAN);

        HttpClient segundoDispositivo = nuevoClienteDispositivo();
        GoogleSimulado.login(segundoDispositivo, baseUrl(), SUB_JUAN, EMAIL_JUAN);

        assertThat(db.queryForObject("SELECT COUNT(*) FROM usuario", Long.class)).isEqualTo(1);
        assertSesionDelContrato(segundoDispositivo, EMAIL_JUAN);
    }

    @Test
    void cerrarSesionInvalidaLaSesionDelDispositivoActual() throws Exception {
        HttpClient dispositivo = nuevoClienteDispositivo();
        GoogleSimulado.login(dispositivo, baseUrl(), SUB_JUAN, EMAIL_JUAN);
        String sessionId = cookieDeSesion(dispositivo);

        // Otro dispositivo del mismo usuario conserva su sesión: el
        // cierre solo invalida la de este dispositivo.
        HttpClient otroDispositivo = nuevoClienteDispositivo();
        GoogleSimulado.login(otroDispositivo, baseUrl(), SUB_JUAN, EMAIL_JUAN);
        String otraSession = cookieDeSesion(otroDispositivo);
        assertThat(otraSession).isNotEqualTo(sessionId);

        HttpResponse<String> respuesta = postLogout(dispositivo);
        assertThat(respuesta.statusCode()).isEqualTo(204);
        assertThat(respuesta.body()).isEmpty();

        // La sesión persistida de este dispositivo ya no existe; la del
        // otro sí.
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM spring_session WHERE session_id = ?",
                Long.class, sessionId)).isZero();
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM spring_session WHERE session_id = ?",
                Long.class, otraSession)).isEqualTo(1);

        JsonNode body = json.readTree(get(dispositivo, "/api/sesion").body());
        assertThat(body.get("usuario").isNull()).isTrue();
        assertSesionDelContrato(otroDispositivo, EMAIL_JUAN);
    }

    @Test
    void cerrarSesionSinSesionResponde204() throws Exception {
        HttpClient dispositivo = nuevoClienteDispositivo();

        HttpResponse<String> respuesta = postLogout(dispositivo);

        assertThat(respuesta.statusCode()).isEqualTo(204);
    }

    @Test
    void cerrarSesionSinTokenCsrfDevuelve403() throws Exception {
        HttpClient dispositivo = nuevoClienteDispositivo();
        GoogleSimulado.login(dispositivo, baseUrl(), SUB_JUAN, EMAIL_JUAN);

        HttpRequest logout = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/logout"))
                .timeout(Duration.ofSeconds(15))
                .POST(BodyPublishers.noBody())
                .build();
        HttpResponse<String> respuesta = dispositivo.send(logout, BodyHandlers.ofString());

        assertThat(respuesta.statusCode()).isEqualTo(403);
        JsonNode body = json.readTree(respuesta.body());
        assertThat(body.get("error").asText()).isEqualTo("CSRF_TOKEN_INVALID");
        // La sesión sigue viva: el rechazo ocurrió antes del cierre.
        assertSesionDelContrato(dispositivo, EMAIL_JUAN);
    }

    @Test
    void crearEnlacesSinSesionSigueFuncionando() throws Exception {
        HttpClient dispositivo = nuevoClienteDispositivo();

        HttpResponse<String> respuesta = postJson(dispositivo,
                "{\"destination\":\"https://ejemplo.com/anonimo\"}");

        assertThat(respuesta.statusCode()).isEqualTo(201);
    }

    /**
     * POST del cierre con su token CSRF (issue #19): mismo doble envío
     * que el POST de enlaces (cookie {@code XSRF-TOKEN} + encabezado
     * {@code X-XSRF-TOKEN}).
     */
    private HttpResponse<String> postLogout(HttpClient dispositivo) throws Exception {
        String csrf = Csrf.obtener(dispositivo, baseUrl());
        HttpRequest logout = Csrf.aplicar(dispositivo,
                        HttpRequest.newBuilder(URI.create(baseUrl() + "/api/logout")), csrf)
                .timeout(Duration.ofSeconds(15))
                .POST(BodyPublishers.noBody())
                .build();
        return dispositivo.send(logout, BodyHandlers.ofString());
    }

    /**
     * El contrato autenticado de {@code GET /api/sesion}: email, namespace
     * vacío hasta que el usuario lo elija y tipo {@code principal}.
     */
    private void assertSesionDelContrato(HttpClient dispositivo, String email) throws Exception {
        HttpResponse<String> respuesta = get(dispositivo, "/api/sesion");
        assertThat(respuesta.statusCode()).isEqualTo(200);
        JsonNode usuario = json.readTree(respuesta.body()).get("usuario");
        assertThat(usuario.get("email").asText()).isEqualTo(email);
        assertThat(usuario.get("namespace").asText()).isEmpty();
        assertThat(usuario.get("tipo").asText()).isEqualTo("principal");
    }

    /**
     * Id de la sesión persistida del dispositivo: la cookie {@code SESSION}
     * viaja codificada en base64url ({@code DefaultCookieSerializer}),
     * así que se decodifica para comparar con
     * {@code spring_session.session_id}.
     */
    private String cookieDeSesion(HttpClient dispositivo) {
        CookieManager almacen = (CookieManager) dispositivo.cookieHandler().orElseThrow();
        return almacen.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("SESSION"))
                .findFirst()
                .map(HttpCookie::getValue)
                .map(valor -> new String(
                        Base64.getUrlDecoder().decode(valor), StandardCharsets.UTF_8))
                .orElse(null);
    }
}
