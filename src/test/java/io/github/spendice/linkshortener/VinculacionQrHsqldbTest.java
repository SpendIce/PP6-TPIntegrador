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

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recorrido de la vinculación por QR (issue #22) sobre el perfil
 * {@code hsqldb} en memoria, sin Docker: la creación del código, la
 * consulta pendiente que no abre sesión, el consumo que materializa la
 * sesión vinculada con su inactividad configurada y el tipo informado por
 * {@code /api/sesion}, el código de un solo uso y el vencimiento moviendo
 * el {@link MutableClock}, el dispositivo con sesión que consume sin
 * degradarla y el reintento tras un fallo de persistencia de la sesión
 * (el consumo corre en la transacción del pedido). La aprobación llega
 * por una fila sembrada (estado {@code APROBADA} + usuario), porque la
 * danza OIDC necesita el proveedor de Testcontainers; el camino aprobado
 * por la sesión principal lo cubre {@code VinculacionQrHttpTest} sobre
 * PostgreSQL.
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
        HttpClient computadora = Dispositivos.nuevo();
        String codigo = pedirCodigo(computadora);
        Long aprobador = sembrarUsuario("google-sub-juan", "juan@ejemplo.com");
        aprobarSembrado(codigo, aprobador);

        HttpResponse<String> aprobada = Dispositivos.get(computadora, baseUrl(), "/api/vinculacion/" + codigo);
        assertThat(aprobada.statusCode()).isEqualTo(200);
        assertThat(json.readTree(aprobada.body()).get("estado").asText()).isEqualTo("aprobada");
        String sessionId = Dispositivos.cookieDeSesion(computadora);
        assertThat(sessionId).isNotNull();

        JsonNode usuario = json.readTree(Dispositivos.get(computadora, baseUrl(), "/api/sesion").body()).get("usuario");
        assertThat(usuario.get("email").asText()).isEqualTo("juan@ejemplo.com");
        assertThat(usuario.get("tipo").asText()).isEqualTo("vinculada");
        assertThat(db.queryForObject(
                "SELECT max_inactive_interval FROM spring_session WHERE session_id = ?",
                Integer.class, sessionId)).isEqualTo(VINCULADA_INACTIVIDAD_SEGUNDOS);

        // El consumo es de un solo uso: repetir la consulta responde
        // vencida y no abre otra sesión.
        assertThat(json.readTree(Dispositivos.get(computadora, baseUrl(), "/api/vinculacion/" + codigo).body())
                .get("estado").asText()).isEqualTo("vencida");
        assertThat(db.queryForObject(
                "SELECT estado FROM codigo_vinculacion WHERE codigo = ?",
                String.class, codigo)).isEqualTo("CONSUMIDA");
    }

    @Test
    void laSesionVinculadaVeLaPaginaPeroNoPuedeAprobar() throws Exception {
        // Una sesión vinculada materializada por el consumo.
        HttpClient vinculada = Dispositivos.nuevo();
        String primerCodigo = pedirCodigo(vinculada);
        aprobarSembrado(primerCodigo, sembrarUsuario("google-sub-juan", "juan@ejemplo.com"));
        Dispositivos.get(vinculada, baseUrl(), "/api/vinculacion/" + primerCodigo);
        assertThat(Dispositivos.cookieDeSesion(vinculada)).isNotNull();

        String segundoCodigo = pedirCodigo(Dispositivos.nuevo());

        // La página y su consulta responden para la vinculada (exige
        // sesión, no principal) y exponen su tipo.
        assertThat(Dispositivos.get(vinculada, baseUrl(), "/vincular/" + segundoCodigo).statusCode()).isEqualTo(200);
        JsonNode confirmacion = json.readTree(
                Dispositivos.get(vinculada, baseUrl(), "/api/vinculacion/" + segundoCodigo + "/confirmacion").body());
        assertThat(confirmacion.get("usuario").get("tipo").asText()).isEqualTo("vinculada");
        assertThat(confirmacion.get("usuario").get("email").asText())
                .isEqualTo("juan@ejemplo.com");

        // La aprobación se rechaza y el código sigue pendiente.
        HttpResponse<String> aprobacion = Dispositivos.postVacio(vinculada, baseUrl(), "/api/vinculacion/" + segundoCodigo + "/aprobar");
        assertThat(aprobacion.statusCode()).isEqualTo(403);
        assertThat(json.readTree(aprobacion.body()).get("error").asText())
                .isEqualTo("NOT_PRINCIPAL_SESSION");
        assertThat(json.readTree(Dispositivos.get(Dispositivos.nuevo(), baseUrl(),
                        "/api/vinculacion/" + segundoCodigo).body())
                .get("estado").asText()).isEqualTo("pendiente");
    }

    @Test
    void unDispositivoConSesionConsumeElCodigoPeroNoDegradaSuSesion() throws Exception {
        // La computadora ya quedó con su sesión vinculada de Juan.
        HttpClient vinculada = Dispositivos.nuevo();
        String primerCodigo = pedirCodigo(vinculada);
        aprobarSembrado(primerCodigo, sembrarUsuario("google-sub-juan", "juan@ejemplo.com"));
        Dispositivos.get(vinculada, baseUrl(), "/api/vinculacion/" + primerCodigo);
        String sesionId = Dispositivos.cookieDeSesion(vinculada);
        assertThat(sesionId).isNotNull();

        // El mismo dispositivo presenta otro código aprobado por Ana: el
        // código es de un solo uso y se consume, pero su sesión no se
        // toca —abrir otra la degradaría— y la respuesta es vencida.
        String segundoCodigo = pedirCodigo(Dispositivos.nuevo());
        aprobarSembrado(segundoCodigo, sembrarUsuario("google-sub-ana", "ana@ejemplo.com"));

        HttpResponse<String> consulta =
                Dispositivos.get(vinculada, baseUrl(), "/api/vinculacion/" + segundoCodigo);
        assertThat(consulta.statusCode()).isEqualTo(200);
        assertThat(json.readTree(consulta.body()).get("estado").asText()).isEqualTo("vencida");
        assertThat(Dispositivos.cookieDeSesion(vinculada)).isEqualTo(sesionId);
        assertThat(db.queryForObject(
                "SELECT estado FROM codigo_vinculacion WHERE codigo = ?",
                String.class, segundoCodigo)).isEqualTo("CONSUMIDA");

        // La sesión sigue siendo la vinculada de Juan, no la de Ana.
        JsonNode usuario = json.readTree(
                Dispositivos.get(vinculada, baseUrl(), "/api/sesion").body()).get("usuario");
        assertThat(usuario.get("email").asText()).isEqualTo("juan@ejemplo.com");
        assertThat(usuario.get("tipo").asText()).isEqualTo("vinculada");
    }

    @Test
    void unFalloAlPersistirLaSesionPermiteReintentarElConsumo() throws Exception {
        HttpClient computadora = Dispositivos.nuevo();
        String codigo = pedirCodigo(computadora);
        aprobarSembrado(codigo, sembrarUsuario("google-sub-juan", "juan@ejemplo.com"));

        // El alta de los atributos falla al cerrar la respuesta: el
        // consumo, que corre en la misma transacción del pedido, revierte
        // y el código sigue APROBADA en vez de quedar CONSUMIDA.
        db.execute("SET TABLE spring_session_attributes READONLY TRUE");
        try {
            HttpResponse<String> fallo =
                    Dispositivos.get(computadora, baseUrl(), "/api/vinculacion/" + codigo);
            assertThat(fallo.statusCode()).isBetween(500, 599);
            assertThat(Dispositivos.cookieDeSesion(computadora)).isNull();
            assertThat(db.queryForObject(
                    "SELECT estado FROM codigo_vinculacion WHERE codigo = ?",
                    String.class, codigo)).isEqualTo("APROBADA");
        } finally {
            db.execute("SET TABLE spring_session_attributes READONLY FALSE");
        }

        HttpResponse<String> reintento =
                Dispositivos.get(computadora, baseUrl(), "/api/vinculacion/" + codigo);
        assertThat(reintento.statusCode()).isEqualTo(200);
        assertThat(json.readTree(reintento.body()).get("estado").asText())
                .isEqualTo("aprobada");
        assertThat(Dispositivos.cookieDeSesion(computadora)).isNotNull();
        assertThat(db.queryForObject(
                "SELECT estado FROM codigo_vinculacion WHERE codigo = ?",
                String.class, codigo)).isEqualTo("CONSUMIDA");
    }

    @Test
    void laConsultaPendienteNoAbreSesionYElVencidoRespondeVencida() throws Exception {
        HttpClient computadora = Dispositivos.nuevo();
        String codigo = pedirCodigo(computadora);

        HttpResponse<String> pendiente = Dispositivos.get(computadora, baseUrl(), "/api/vinculacion/" + codigo);
        assertThat(pendiente.statusCode()).isEqualTo(200);
        assertThat(json.readTree(pendiente.body()).get("estado").asText()).isEqualTo("pendiente");
        assertThat(Dispositivos.cookieDeSesion(computadora)).isNull();

        clock.set(T0.plusSeconds(61));
        assertThat(json.readTree(Dispositivos.get(computadora, baseUrl(), "/api/vinculacion/" + codigo).body())
                .get("estado").asText()).isEqualTo("vencida");
        assertThat(Dispositivos.cookieDeSesion(computadora)).isNull();
    }

    @Test
    void codigoInexistenteResponde404YLaPaginaExigeSesion() throws Exception {
        HttpClient dispositivo = Dispositivos.nuevo();

        HttpResponse<String> inexistente = Dispositivos.get(dispositivo, baseUrl(), "/api/vinculacion/no-existe");
        assertThat(inexistente.statusCode()).isEqualTo(404);
        assertThat(json.readTree(inexistente.body()).get("error").asText())
                .isEqualTo("LINK_CODE_INVALID");

        HttpResponse<String> pagina = Dispositivos.get(dispositivo, baseUrl(), "/vincular/algo");
        assertThat(pagina.statusCode()).isEqualTo(302);
        assertThat(pagina.headers().firstValue("Location").orElse(""))
                .contains("/oauth2/authorization/google");
    }

    @Test
    void sinSesionLaConfirmacionYLaAprobacionResponden401() throws Exception {
        HttpClient dispositivo = Dispositivos.nuevo();
        String codigo = pedirCodigo(Dispositivos.nuevo());

        HttpResponse<String> confirmacion =
                Dispositivos.get(dispositivo, baseUrl(), "/api/vinculacion/" + codigo + "/confirmacion");
        assertThat(confirmacion.statusCode()).isEqualTo(401);
        assertThat(json.readTree(confirmacion.body()).get("error").asText())
                .isEqualTo("NOT_AUTHENTICATED");

        HttpResponse<String> aprobacion = Dispositivos.postVacio(dispositivo, baseUrl(), "/api/vinculacion/" + codigo + "/aprobar");
        assertThat(aprobacion.statusCode()).isEqualTo(401);
        assertThat(json.readTree(aprobacion.body()).get("error").asText())
                .isEqualTo("NOT_AUTHENTICATED");
    }

    // ---- Helpers ----

    private String pedirCodigo(HttpClient dispositivo) throws Exception {
        HttpResponse<String> respuesta = Dispositivos.postVacio(dispositivo, baseUrl(), "/api/vinculacion");
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

    private String baseUrl() {
        return "http://localhost:" + port;
    }
}
