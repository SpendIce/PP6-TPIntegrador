package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recorrido de la vinculación por QR (issue #22) sobre el perfil
 * {@code hsqldb} en memoria, sin Docker: la creación del código, la
 * consulta pendiente que no abre sesión, el consumo que materializa la
 * sesión vinculada con su inactividad configurada y el tipo informado por
 * {@code /api/sesion}, el código de un solo uso y el vencimiento moviendo
 * el {@link MutableClock}. La aprobación llega por una fila sembrada
 * (estado {@code APROBADA} + usuario), porque la danza OIDC necesita el
 * proveedor de Testcontainers; el camino aprobado por la sesión principal
 * lo cubre {@code VinculacionQrHttpTest} sobre PostgreSQL.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {LinkShortenerApplication.class, ClockTestConfiguration.class},
        properties = {
                "spring.datasource.url=jdbc:hsqldb:mem:vinculacion",
                "spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.security.oauth2.client.registration.google.client-id=" + GoogleSimulado.CLIENT_ID,
                "spring.security.oauth2.client.registration.google.client-secret=" + GoogleSimulado.CLIENT_SECRET,
        })
@ActiveProfiles("hsqldb")
class VinculacionQrHsqldbTest {

    private static final Instant T0 = Instant.parse("2026-10-05T12:00:00Z");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final int VINCULADA_INACTIVIDAD_SEGUNDOS = (int) Duration.ofHours(12).toSeconds();

    @LocalServerPort
    int port;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate db;
    @Autowired
    MutableClock clock;

    @BeforeEach
    void limpiar() {
        // HSQLDB trunca de a una tabla: DELETE en orden de las FK.
        for (String tabla : List.of("spring_session_attributes", "spring_session",
                "codigo_vinculacion", "asignacion_personal", "asignacion", "alias",
                "usuario")) {
            db.update("DELETE FROM " + tabla);
        }
        db.update("UPDATE generador_alias SET proximo_indice = 0");
        clock.set(T0);
    }

    @Test
    void consumirElCodigoAprobadoAbreLaSesionVinculadaDelAprobador() throws Exception {
        HttpClient computadora = dispositivo();
        String codigo = pedirCodigo(computadora);
        Long aprobador = sembrarUsuario("google-sub-juan", "juan@ejemplo.com");
        aprobarSembrado(codigo, aprobador);

        HttpResponse<String> aprobada = get(computadora, "/api/vinculacion/" + codigo);
        assertThat(aprobada.statusCode()).isEqualTo(200);
        assertThat(json.readTree(aprobada.body()).get("estado").asText()).isEqualTo("aprobada");
        String sessionId = cookieDeSesion(computadora);
        assertThat(sessionId).isNotNull();

        JsonNode usuario = json.readTree(get(computadora, "/api/sesion").body()).get("usuario");
        assertThat(usuario.get("email").asText()).isEqualTo("juan@ejemplo.com");
        assertThat(usuario.get("tipo").asText()).isEqualTo("vinculada");
        assertThat(db.queryForObject(
                "SELECT max_inactive_interval FROM spring_session WHERE session_id = ?",
                Integer.class, sessionId)).isEqualTo(VINCULADA_INACTIVIDAD_SEGUNDOS);

        // El consumo es de un solo uso: repetir la consulta responde
        // vencida y no abre otra sesión.
        assertThat(json.readTree(get(computadora, "/api/vinculacion/" + codigo).body())
                .get("estado").asText()).isEqualTo("vencida");
        assertThat(db.queryForObject(
                "SELECT estado FROM codigo_vinculacion WHERE codigo = ?",
                String.class, codigo)).isEqualTo("CONSUMIDA");
    }

    @Test
    void laSesionVinculadaVeLaPaginaPeroNoPuedeAprobar() throws Exception {
        // Una sesión vinculada materializada por el consumo.
        HttpClient vinculada = dispositivo();
        String primerCodigo = pedirCodigo(vinculada);
        aprobarSembrado(primerCodigo, sembrarUsuario("google-sub-juan", "juan@ejemplo.com"));
        get(vinculada, "/api/vinculacion/" + primerCodigo);
        assertThat(cookieDeSesion(vinculada)).isNotNull();

        String segundoCodigo = pedirCodigo(dispositivo());

        // La página y su consulta responden para la vinculada (exige
        // sesión, no principal) y exponen su tipo.
        assertThat(get(vinculada, "/vincular/" + segundoCodigo).statusCode()).isEqualTo(200);
        JsonNode confirmacion = json.readTree(
                get(vinculada, "/api/vinculacion/" + segundoCodigo + "/confirmacion").body());
        assertThat(confirmacion.get("usuario").get("tipo").asText()).isEqualTo("vinculada");
        assertThat(confirmacion.get("usuario").get("email").asText())
                .isEqualTo("juan@ejemplo.com");

        // La aprobación se rechaza y el código sigue pendiente.
        HttpResponse<String> aprobacion = postVacio(vinculada,
                "/api/vinculacion/" + segundoCodigo + "/aprobar");
        assertThat(aprobacion.statusCode()).isEqualTo(403);
        assertThat(json.readTree(aprobacion.body()).get("error").asText())
                .isEqualTo("NOT_PRINCIPAL_SESSION");
        assertThat(json.readTree(get(dispositivo(), "/api/vinculacion/" + segundoCodigo).body())
                .get("estado").asText()).isEqualTo("pendiente");
    }

    @Test
    void laConsultaPendienteNoAbreSesionYElVencidoRespondeVencida() throws Exception {
        HttpClient computadora = dispositivo();
        String codigo = pedirCodigo(computadora);

        HttpResponse<String> pendiente = get(computadora, "/api/vinculacion/" + codigo);
        assertThat(pendiente.statusCode()).isEqualTo(200);
        assertThat(json.readTree(pendiente.body()).get("estado").asText()).isEqualTo("pendiente");
        assertThat(cookieDeSesion(computadora)).isNull();

        clock.set(T0.plusSeconds(61));
        assertThat(json.readTree(get(computadora, "/api/vinculacion/" + codigo).body())
                .get("estado").asText()).isEqualTo("vencida");
        assertThat(cookieDeSesion(computadora)).isNull();
    }

    @Test
    void codigoInexistenteResponde404YLaPaginaExigeSesion() throws Exception {
        HttpClient dispositivo = dispositivo();

        HttpResponse<String> inexistente = get(dispositivo, "/api/vinculacion/no-existe");
        assertThat(inexistente.statusCode()).isEqualTo(404);
        assertThat(json.readTree(inexistente.body()).get("error").asText())
                .isEqualTo("LINK_CODE_INVALID");

        HttpResponse<String> pagina = get(dispositivo, "/vincular/algo");
        assertThat(pagina.statusCode()).isEqualTo(302);
        assertThat(pagina.headers().firstValue("Location").orElse(""))
                .contains("/oauth2/authorization/google");
    }

    @Test
    void sinSesionLaConfirmacionYLaAprobacionResponden401() throws Exception {
        HttpClient dispositivo = dispositivo();
        String codigo = pedirCodigo(dispositivo());

        HttpResponse<String> confirmacion =
                get(dispositivo, "/api/vinculacion/" + codigo + "/confirmacion");
        assertThat(confirmacion.statusCode()).isEqualTo(401);
        assertThat(json.readTree(confirmacion.body()).get("error").asText())
                .isEqualTo("NOT_AUTHENTICATED");

        HttpResponse<String> aprobacion = postVacio(dispositivo,
                "/api/vinculacion/" + codigo + "/aprobar");
        assertThat(aprobacion.statusCode()).isEqualTo(401);
        assertThat(json.readTree(aprobacion.body()).get("error").asText())
                .isEqualTo("NOT_AUTHENTICATED");
    }

    // ---- Helpers ----

    private String pedirCodigo(HttpClient dispositivo) throws Exception {
        HttpResponse<String> respuesta = postVacio(dispositivo, "/api/vinculacion");
        assertThat(respuesta.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(respuesta.body());
        String codigo = body.get("codigo").asText();
        assertThat(body.get("confirmacionUrl").asText()).endsWith("/vincular/" + codigo);
        return codigo;
    }

    /** La aprobación sembrada: la fila queda como la deja una principal. */
    private void aprobarSembrado(String codigo, Long usuarioId) {
        db.update("UPDATE codigo_vinculacion SET estado = 'APROBADA', usuario_id = ?"
                + " WHERE codigo = ?", usuarioId, codigo);
    }

    private Long sembrarUsuario(String googleSub, String email) {
        db.update("INSERT INTO usuario (google_sub, email) VALUES (?, ?)", googleSub, email);
        return db.queryForObject("SELECT id FROM usuario WHERE google_sub = ?",
                Long.class, googleSub);
    }

    private HttpResponse<String> postVacio(HttpClient dispositivo, String path)
            throws Exception {
        String csrf = Csrf.obtener(dispositivo, baseUrl());
        HttpRequest request = Csrf.aplicar(dispositivo,
                        HttpRequest.newBuilder(URI.create(baseUrl() + path)), csrf)
                .timeout(REQUEST_TIMEOUT)
                .POST(BodyPublishers.noBody())
                .build();
        return dispositivo.send(request, BodyHandlers.ofString());
    }

    private HttpResponse<String> get(HttpClient dispositivo, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .GET().timeout(REQUEST_TIMEOUT).build();
        return dispositivo.send(request, BodyHandlers.ofString());
    }

    private HttpClient dispositivo() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build();
    }

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

    private String baseUrl() {
        return "http://localhost:" + port;
    }
}
