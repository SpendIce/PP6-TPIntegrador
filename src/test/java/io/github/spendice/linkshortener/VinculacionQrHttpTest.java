package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vinculación por QR (issue #22) sobre PostgreSQL real (Testcontainers,
 * ADR 0004) con Google reemplazado por el proveedor OIDC simulado
 * ({@link GoogleSimulado}, ADR 0007) y un reloj de servidor controlable
 * ({@link MutableClock}) para el vencimiento del código sin esperas
 * reales. Los dos dispositivos del flujo son dos clientes con almacenes
 * de cookies separados: la computadora que espera (sin sesión) y el
 * celular con la sesión principal que aprueba. Reutiliza la infraestructura
 * PostgreSQL y el ciclo de vida de {@link HttpApiFixture}; el reloj mutable
 * entra solo a este contexto con {@code @Import}, porque el resto de las
 * suites del fixture verifica vencimientos contra el reloj real.
 */
@Import(ClockTestConfiguration.class)
class VinculacionQrHttpTest extends HttpApiFixture {

    private static final String PUBLIC_BASE = "http://192.168.50.10:8080";
    private static final Instant T0 = Instant.parse("2026-10-05T12:00:00Z");
    private static final Duration CODIGO_TTL = Duration.ofSeconds(60);
    private static final int VINCULADA_INACTIVIDAD_SEGUNDOS =
            (int) Duration.ofHours(12).toSeconds();

    private static final String SUB_JUAN = "google-sub-vinculacion-juan";
    private static final String EMAIL_JUAN = "juan@ejemplo.com";

    @Autowired
    MutableClock clock;

    @BeforeEach
    void relojEnT0() {
        clock.set(T0);
    }

    @Test
    void laAprobacionDelCelularAbreLaSesionVinculadaEnLaComputadora() throws Exception {
        HttpClient computadora = Dispositivos.nuevo();
        HttpClient celular = sesionDe(SUB_JUAN, EMAIL_JUAN);

        // La computadora que espera pide el código: el QR codifica la
        // URL de confirmación compuesta sobre la dirección pública.
        String codigo = pedirCodigoVinculacion(computadora);

        // La consulta informa pendiente y no abre ninguna sesión.
        assertThat(estadoDe(computadora, codigo).get("estado").asText()).isEqualTo("pendiente");
        assertThat(Dispositivos.cookieDeSesion(computadora)).isNull();

        // El celular abre la confirmación: muestra al usuario de su
        // sesión principal, sin aprobar todavía.
        assertThat(Dispositivos.get(celular, baseUrl(), "/vincular/" + codigo).statusCode()).isEqualTo(200);
        JsonNode confirmacion = json.readTree(
                Dispositivos.get(celular, baseUrl(), "/api/vinculacion/" + codigo + "/confirmacion").body());
        assertThat(confirmacion.get("estado").asText()).isEqualTo("pendiente");
        assertThat(confirmacion.get("usuario").get("email").asText()).isEqualTo(EMAIL_JUAN);
        assertThat(confirmacion.get("usuario").get("tipo").asText()).isEqualTo("principal");

        // La aprobación es una acción explícita del celular.
        HttpResponse<String> aprobacion = Dispositivos.postVacio(celular, baseUrl(), "/api/vinculacion/" + codigo + "/aprobar");
        assertThat(aprobacion.statusCode()).isEqualTo(200);
        assertThat(json.readTree(aprobacion.body()).get("estado").asText())
                .isEqualTo("aprobada");

        // La consulta siguiente de la computadora consume el código y
        // abre la sesión vinculada del usuario que aprobó.
        HttpResponse<String> aprobada = Dispositivos.get(computadora, baseUrl(), "/api/vinculacion/" + codigo);
        assertThat(aprobada.statusCode()).isEqualTo(200);
        assertThat(json.readTree(aprobada.body()).get("estado").asText())
                .isEqualTo("aprobada");
        String sessionId = Dispositivos.cookieDeSesion(computadora);
        assertThat(sessionId).isNotNull();

        // La sesión informa el usuario que aprobó y el tipo vinculada.
        JsonNode usuario = json.readTree(Dispositivos.get(computadora, baseUrl(), "/api/sesion").body()).get("usuario");
        assertThat(usuario.get("email").asText()).isEqualTo(EMAIL_JUAN);
        assertThat(usuario.get("tipo").asText()).isEqualTo("vinculada");

        // Persistida con la inactividad configurada de la vinculada
        // (PT12H) y el tipo registrado como atributo.
        assertThat(db.queryForObject(
                "SELECT max_inactive_interval FROM spring_session WHERE session_id = ?",
                Integer.class, sessionId)).isEqualTo(VINCULADA_INACTIVIDAD_SEGUNDOS);
        List<String> atributos = db.queryForList(
                "SELECT attribute_name FROM spring_session_attributes"
                        + " WHERE session_primary_id = (SELECT primary_id FROM spring_session"
                        + " WHERE session_id = ?)",
                String.class, sessionId);
        assertThat(atributos).contains("sesionTipo", "usuarioId");

        // El código quedó consumido con el usuario que aprobó.
        assertThat(db.queryForObject(
                "SELECT estado FROM codigo_vinculacion WHERE codigo = ?",
                String.class, codigo)).isEqualTo("CONSUMIDA");
        assertThat(db.queryForObject(
                "SELECT usuario_id FROM codigo_vinculacion WHERE codigo = ?",
                Long.class, codigo))
                .isEqualTo(db.queryForObject(
                        "SELECT id FROM usuario WHERE google_sub = ?", Long.class, SUB_JUAN));
    }

    @Test
    void unCodigoUsadoNoAbreOtraSesion() throws Exception {
        HttpClient computadora = Dispositivos.nuevo();
        HttpClient celular = sesionDe(SUB_JUAN, EMAIL_JUAN);
        String codigo = pedirCodigoVinculacion(computadora);
        verConfirmacion(celular, codigo);
        Dispositivos.postVacio(celular, baseUrl(), "/api/vinculacion/" + codigo + "/aprobar");
        Dispositivos.get(computadora, baseUrl(), "/api/vinculacion/" + codigo);
        assertThat(Dispositivos.cookieDeSesion(computadora)).isNotNull();
        long sesiones = contarSesiones();

        // Repetir la consulta con el código ya consumido responde
        // vencida y no crea otra sesión; tampoco la de otro dispositivo.
        assertThat(estadoDe(computadora, codigo).get("estado").asText()).isEqualTo("vencida");
        HttpClient otro = Dispositivos.nuevo();
        assertThat(estadoDe(otro, codigo).get("estado").asText()).isEqualTo("vencida");
        assertThat(Dispositivos.cookieDeSesion(otro)).isNull();
        assertThat(contarSesiones()).isEqualTo(sesiones);

        // Y aprobarlo otra vez lo rechaza.
        assertError(Dispositivos.postVacio(celular, baseUrl(), "/api/vinculacion/" + codigo + "/aprobar"),
                404, "LINK_CODE_INVALID");
    }

    @Test
    void unCodigoVencidoNoAbreNingunaSesion() throws Exception {
        HttpClient computadora = Dispositivos.nuevo();
        HttpClient celular = sesionDe(SUB_JUAN, EMAIL_JUAN);
        String codigo = pedirCodigoVinculacion(computadora);

        // El código sigue aprobable un instante antes del límite.
        clock.set(T0.plus(CODIGO_TTL).minusNanos(1));
        assertThat(estadoDe(computadora, codigo).get("estado").asText()).isEqualTo("pendiente");

        clock.set(T0.plus(CODIGO_TTL).plusSeconds(1));
        assertThat(estadoDe(computadora, codigo).get("estado").asText()).isEqualTo("vencida");
        assertThat(Dispositivos.cookieDeSesion(computadora)).isNull();
        assertError(Dispositivos.postVacio(celular, baseUrl(), "/api/vinculacion/" + codigo + "/aprobar"),
                404, "LINK_CODE_INVALID");
        assertError(Dispositivos.get(celular, baseUrl(), "/api/vinculacion/" + codigo + "/confirmacion"),
                404, "LINK_CODE_INVALID");
    }

    @Test
    void laAprobacionLaPuedeHacerSoloElUsuarioAlQueSeLePresento() throws Exception {
        HttpClient computadora = Dispositivos.nuevo();
        HttpClient juan = sesionDe(SUB_JUAN, EMAIL_JUAN);
        HttpClient ana = sesionDe("google-sub-vinculacion-ana", "ana@ejemplo.com");
        String codigo = pedirCodigoVinculacion(computadora);

        // Juan abre la confirmación: el código queda presentado a él.
        assertThat(verConfirmacion(juan, codigo).get("usuario").get("email").asText())
                .isEqualTo(EMAIL_JUAN);

        // Ana, con otra sesión principal, no puede ni ver ni aprobar el
        // código presentado a Juan: le responde como a uno inválido.
        assertError(Dispositivos.get(ana, baseUrl(), "/api/vinculacion/" + codigo + "/confirmacion"),
                404, "LINK_CODE_INVALID");
        assertError(Dispositivos.postVacio(ana, baseUrl(), "/api/vinculacion/" + codigo + "/aprobar"),
                404, "LINK_CODE_INVALID");
        assertThat(estadoDe(computadora, codigo).get("estado").asText()).isEqualTo("pendiente");

        // Juan, al que se le mostró el código, sí lo aprueba y la
        // computadora queda con la sesión vinculada de él.
        assertThat(Dispositivos.postVacio(juan, baseUrl(), "/api/vinculacion/" + codigo + "/aprobar")
                .statusCode()).isEqualTo(200);
        assertThat(estadoDe(computadora, codigo).get("estado").asText()).isEqualTo("aprobada");
        JsonNode usuario = json.readTree(
                Dispositivos.get(computadora, baseUrl(), "/api/sesion").body()).get("usuario");
        assertThat(usuario.get("email").asText()).isEqualTo(EMAIL_JUAN);
        assertThat(usuario.get("tipo").asText()).isEqualTo("vinculada");
    }

    @Test
    void unaAprobacionSinConfirmacionPreviaSeRechaza() throws Exception {
        HttpClient computadora = Dispositivos.nuevo();
        HttpClient juan = sesionDe(SUB_JUAN, EMAIL_JUAN);
        String codigo = pedirCodigoVinculacion(computadora);

        // El POST sin que la confirmación se haya mostrado a Juan no
        // liga aprobación alguna: el código sigue pendiente.
        assertError(Dispositivos.postVacio(juan, baseUrl(), "/api/vinculacion/" + codigo + "/aprobar"),
                404, "LINK_CODE_INVALID");
        assertThat(estadoDe(computadora, codigo).get("estado").asText()).isEqualTo("pendiente");
        assertThat(Dispositivos.cookieDeSesion(computadora)).isNull();

        // Una vez que la confirmación se le presentó, sí aprueba.
        verConfirmacion(juan, codigo);
        assertThat(Dispositivos.postVacio(juan, baseUrl(), "/api/vinculacion/" + codigo + "/aprobar")
                .statusCode()).isEqualTo(200);
    }

    @Test
    void unCodigoAprobadoQueVencioAntesDeLaConsultaNoAbreSesion() throws Exception {
        HttpClient computadora = Dispositivos.nuevo();
        HttpClient celular = sesionDe(SUB_JUAN, EMAIL_JUAN);
        String codigo = pedirCodigoVinculacion(computadora);
        verConfirmacion(celular, codigo);
        Dispositivos.postVacio(celular, baseUrl(), "/api/vinculacion/" + codigo + "/aprobar");

        // La aprobación quedó registrada, pero el código venció antes de
        // que el dispositivo la consuma: ya no abre ninguna sesión.
        clock.set(T0.plus(CODIGO_TTL).plusSeconds(1));
        assertThat(estadoDe(computadora, codigo).get("estado").asText()).isEqualTo("vencida");
        assertThat(Dispositivos.cookieDeSesion(computadora)).isNull();
    }

    @Test
    void unFalloAlPersistirLaSesionPermiteReintentarElConsumo() throws Exception {
        HttpClient computadora = Dispositivos.nuevo();
        HttpClient celular = sesionDe(SUB_JUAN, EMAIL_JUAN);
        String codigo = pedirCodigoVinculacion(computadora);
        verConfirmacion(celular, codigo);
        Dispositivos.postVacio(celular, baseUrl(), "/api/vinculacion/" + codigo + "/aprobar");
        db.execute("CREATE FUNCTION rechazar_atributo_sesion() RETURNS trigger AS $$"
                + "BEGIN RAISE EXCEPTION 'fallo de persistencia simulado'; END;"
                + "$$ LANGUAGE plpgsql");
        db.execute("CREATE TRIGGER fallo_sesion BEFORE INSERT ON spring_session_attributes"
                + " FOR EACH ROW EXECUTE FUNCTION rechazar_atributo_sesion()");

        try {
            HttpResponse<String> fallo = Dispositivos.get(
                    computadora, baseUrl(), "/api/vinculacion/" + codigo);
            assertThat(fallo.statusCode()).isBetween(500, 599);
            assertThat(Dispositivos.cookieDeSesion(computadora)).isNull();
            assertThat(db.queryForObject(
                    "SELECT estado FROM codigo_vinculacion WHERE codigo = ?",
                    String.class, codigo)).isEqualTo("APROBADA");
        } finally {
            db.execute("DROP TRIGGER fallo_sesion ON spring_session_attributes");
            db.execute("DROP FUNCTION rechazar_atributo_sesion()");
        }

        HttpResponse<String> reintento = Dispositivos.get(
                computadora, baseUrl(), "/api/vinculacion/" + codigo);
        assertThat(reintento.statusCode()).isEqualTo(200);
        assertThat(json.readTree(reintento.body()).get("estado").asText())
                .isEqualTo("aprobada");
        assertThat(Dispositivos.cookieDeSesion(computadora)).isNotNull();
        assertThat(db.queryForObject(
                "SELECT estado FROM codigo_vinculacion WHERE codigo = ?",
                String.class, codigo)).isEqualTo("CONSUMIDA");
    }

    @Test
    void unCodigoInexistenteNoAbreSesionYResponde404() throws Exception {
        HttpClient computadora = Dispositivos.nuevo();
        HttpClient celular = sesionDe(SUB_JUAN, EMAIL_JUAN);

        assertError(Dispositivos.get(computadora, baseUrl(), "/api/vinculacion/no-existe"), 404, "LINK_CODE_INVALID");
        assertThat(Dispositivos.cookieDeSesion(computadora)).isNull();
        assertError(Dispositivos.postVacio(celular, baseUrl(), "/api/vinculacion/no-existe/aprobar"),
                404, "LINK_CODE_INVALID");
        assertError(Dispositivos.get(celular, baseUrl(), "/api/vinculacion/no-existe/confirmacion"),
                404, "LINK_CODE_INVALID");
    }

    @Test
    void cadaPedidoEmiteUnCodigoDistintoEInadivinable() throws Exception {
        HttpClient computadora = Dispositivos.nuevo();

        String primero = pedirCodigoVinculacion(computadora);
        String segundo = pedirCodigoVinculacion(computadora);

        assertThat(segundo).isNotEqualTo(primero);
        // 256 bits en base64url sin relleno: 43 símbolos.
        assertThat(primero).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void unaSesionVinculadaNoPuedeAprobarOtraVinculacion() throws Exception {
        // Primera vinculación: la computadora queda con sesión vinculada.
        HttpClient computadora = Dispositivos.nuevo();
        HttpClient celular = sesionDe(SUB_JUAN, EMAIL_JUAN);
        String primerCodigo = pedirCodigoVinculacion(computadora);
        verConfirmacion(celular, primerCodigo);
        Dispositivos.postVacio(celular, baseUrl(), "/api/vinculacion/" + primerCodigo + "/aprobar");
        Dispositivos.get(computadora, baseUrl(), "/api/vinculacion/" + primerCodigo);
        assertThat(Dispositivos.cookieDeSesion(computadora)).isNotNull();

        // Otra computadora pide un código y la vinculada intenta
        // aprobarlo: la confirmación expone su tipo y el POST se rechaza.
        HttpClient otraComputadora = Dispositivos.nuevo();
        String segundoCodigo = pedirCodigoVinculacion(otraComputadora);

        JsonNode confirmacion = json.readTree(
                Dispositivos.get(computadora, baseUrl(), "/api/vinculacion/" + segundoCodigo + "/confirmacion").body());
        assertThat(confirmacion.get("usuario").get("tipo").asText()).isEqualTo("vinculada");
        assertError(Dispositivos.postVacio(computadora, baseUrl(), "/api/vinculacion/" + segundoCodigo + "/aprobar"),
                403, "NOT_PRINCIPAL_SESSION");

        // El código sigue pendiente: el rechazo no lo marcó aprobado.
        assertThat(estadoDe(otraComputadora, segundoCodigo).get("estado").asText())
                .isEqualTo("pendiente");
        assertThat(Dispositivos.cookieDeSesion(otraComputadora)).isNull();
    }

    @Test
    void sinSesionLaConfirmacionYLaAprobacionSeRechazan() throws Exception {
        HttpClient anonimo = Dispositivos.nuevo();
        String codigo = pedirCodigoVinculacion(Dispositivos.nuevo());

        assertError(Dispositivos.get(anonimo, baseUrl(), "/api/vinculacion/" + codigo + "/confirmacion"),
                401, "NOT_AUTHENTICATED");
        assertError(Dispositivos.postVacio(anonimo, baseUrl(), "/api/vinculacion/" + codigo + "/aprobar"),
                401, "NOT_AUTHENTICATED");

        // El código sigue pendiente: el rechazo no lo tocó.
        assertThat(estadoDe(anonimo, codigo).get("estado").asText()).isEqualTo("pendiente");
    }

    @Test
    void laPaginaDeConfirmacionExigeSesionYVuelveTrasElLogin() throws Exception {
        HttpClient computadora = Dispositivos.nuevo();
        String codigo = pedirCodigoVinculacion(computadora);

        // El celular sin sesión abre la página del QR: el servidor lo
        // manda al login con Google.
        HttpClient celular = Dispositivos.nuevo();
        HttpResponse<String> pagina = Dispositivos.get(celular, baseUrl(), "/vincular/" + codigo);
        assertThat(pagina.statusCode()).isEqualTo(302);
        assertThat(pagina.headers().firstValue("Location").orElse(""))
                .contains("/oauth2/authorization/google");

        // Al completar el login, la navegación vuelve a la confirmación
        // del código escaneado.
        HttpResponse<String> vuelta = GoogleSimulado.login(celular, baseUrl(),
                SUB_JUAN, EMAIL_JUAN);
        assertThat(vuelta.statusCode()).isEqualTo(302);
        // Spring agrega `?continue` al request cacheado y lo reinyecta en la
        // vuelta: la dirección sigue siendo la confirmación del código.
        assertThat(vuelta.headers().firstValue("Location").orElse(""))
                .contains("/vincular/" + codigo);
        assertThat(Dispositivos.get(celular, baseUrl(), "/vincular/" + codigo).statusCode()).isEqualTo(200);
    }

    @Test
    void laPaginaDeConfirmacionCargaSusRecursos() throws Exception {
        // Con rutas relativas el navegador pediría /vincular/styles.css y
        // /vincular/vincular.js, que no son estáticos: la página se quedaba
        // en «Consultando el código…» sin botón de aprobación.
        HttpClient computadora = Dispositivos.nuevo();
        HttpClient juan = usuarioConNamespace(SUB_JUAN, EMAIL_JUAN, "juan");
        String codigo = pedirCodigoVinculacion(computadora);

        HttpResponse<String> pagina = Dispositivos.get(juan, baseUrl(), "/vincular/" + codigo);

        assertThat(pagina.statusCode()).isEqualTo(200);
        assertThat(pagina.body()).contains("href=\"/styles.css\"");
        assertThat(pagina.body()).contains("src=\"/vincular.js\"");
        assertThat(Dispositivos.get(juan, baseUrl(), "/vincular.js").statusCode()).isEqualTo(200);
        assertThat(Dispositivos.get(juan, baseUrl(), "/vinculacion-timers.js").statusCode()).isEqualTo(200);
    }

    @Test
    void laConfirmacionMuestraElNamespaceOElEmail() throws Exception {
        // Juan ya eligió su namespace: la confirmación lo muestra.
        HttpClient computadora = Dispositivos.nuevo();
        HttpClient juan = usuarioConNamespace(SUB_JUAN, EMAIL_JUAN, "juan");
        String codigo = pedirCodigoVinculacion(computadora);

        JsonNode usuario = json.readTree(
                Dispositivos.get(juan, baseUrl(), "/api/vinculacion/" + codigo + "/confirmacion").body()).get("usuario");
        assertThat(usuario.get("namespace").asText()).isEqualTo("juan");
        assertThat(usuario.get("sugerencia").asText()).isEmpty();

        // Ana no eligió namespace: la página muestra su email.
        HttpClient computadora2 = Dispositivos.nuevo();
        HttpClient ana = sesionDe("google-sub-vinculacion-ana", "ana@ejemplo.com");
        String codigo2 = pedirCodigoVinculacion(computadora2);

        JsonNode usuario2 = json.readTree(
                Dispositivos.get(ana, baseUrl(), "/api/vinculacion/" + codigo2 + "/confirmacion").body()).get("usuario");
        assertThat(usuario2.get("namespace").asText()).isEmpty();
        assertThat(usuario2.get("email").asText()).isEqualTo("ana@ejemplo.com");
        assertThat(usuario2.get("sugerencia").asText()).isEqualTo("ana");
    }

    // ---- Helpers ----

    /**
     * POST del pedido de código con su CSRF (sin cuerpo): responde 201 y
     * el contrato {@code {codigo, confirmacionUrl, expiresAt}}. La URL
     * de confirmación se compone sobre la dirección pública con
     * {@code /vincular/{codigo}} y el vencimiento es el TTL del clock.
     */
    private String pedirCodigoVinculacion(HttpClient dispositivo) throws Exception {
        HttpResponse<String> respuesta = Dispositivos.postVacio(dispositivo, baseUrl(), "/api/vinculacion");
        assertThat(respuesta.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(respuesta.body());
        String codigo = body.get("codigo").asText();
        assertThat(body.get("confirmacionUrl").asText())
                .isEqualTo(PUBLIC_BASE + "/vincular/" + codigo);
        assertThat(Instant.parse(body.get("expiresAt").asText()))
                .isEqualTo(clock.instant().plus(CODIGO_TTL));
        return codigo;
    }

    /**
     * La confirmación vista por el dispositivo que aprueba: 200 con el
     * cuerpo {@code {estado, usuario}} y la ligadura del código a su
     * usuario, como hace la página al cargar.
     */
    private JsonNode verConfirmacion(HttpClient dispositivo, String codigo) throws Exception {
        HttpResponse<String> respuesta = Dispositivos.get(dispositivo, baseUrl(),
                "/api/vinculacion/" + codigo + "/confirmacion");
        assertThat(respuesta.statusCode()).isEqualTo(200);
        return json.readTree(respuesta.body());
    }

    /** Consulta del estado por el dispositivo que espera: 200 con cuerpo. */
    private JsonNode estadoDe(HttpClient dispositivo, String codigo) throws Exception {
        HttpResponse<String> respuesta = Dispositivos.get(dispositivo, baseUrl(), "/api/vinculacion/" + codigo);
        assertThat(respuesta.statusCode()).isEqualTo(200);
        return json.readTree(respuesta.body());
    }

    /** Inicia sesión principal con Google simulado en un dispositivo. */
    private HttpClient sesionDe(String sub, String email) throws Exception {
        HttpClient dispositivo = Dispositivos.nuevo();
        GoogleSimulado.login(dispositivo, baseUrl(), sub, email);
        return dispositivo;
    }

    /** Sesión del usuario con su namespace ya elegido. */
    private HttpClient usuarioConNamespace(String sub, String email, String namespace)
            throws Exception {
        HttpClient dispositivo = sesionDe(sub, email);
        String csrf = Csrf.obtener(dispositivo, baseUrl());
        HttpRequest alta = Csrf.aplicar(dispositivo,
                        HttpRequest.newBuilder(URI.create(baseUrl() + "/api/namespace")), csrf)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(BodyPublishers.ofString("{\"namespace\":\"" + namespace + "\"}"))
                .build();
        HttpResponse<String> respuesta = dispositivo.send(alta, BodyHandlers.ofString());
        assertThat(respuesta.statusCode()).as("alta del namespace %s", namespace).isEqualTo(200);
        return dispositivo;
    }

    private long contarSesiones() {
        return db.queryForObject("SELECT COUNT(*) FROM spring_session", Long.class);
    }
}
