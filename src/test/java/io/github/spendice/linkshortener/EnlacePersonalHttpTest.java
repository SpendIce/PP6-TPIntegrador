package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enlaces personales con alias personalizado (issue #21) sobre
 * PostgreSQL real (Testcontainers, ADR 0004) y el proveedor OIDC
 * simulado (ADR 0007): creación con sesión y namespace, formato y
 * normalización a minúsculas, unicidad del alias vigente por usuario,
 * reutilización tras el vencimiento conservando el historial (ADR
 * 0003), resolución de dos segmentos y convivencia con el alias de un
 * segmento. La infraestructura compartida está en
 * {@link HttpApiFixture}.
 */
class EnlacePersonalHttpTest extends HttpApiFixture {

    private static final String SUB_JUAN = "google-sub-enlace-juan";
    private static final String EMAIL_JUAN = "juan@ejemplo.com";
    private static final String SUB_PEDRO = "google-sub-enlace-pedro";
    private static final String EMAIL_PEDRO = "pedro@ejemplo.com";
    private static final String MENSAJE_404 = "Este enlace no existe o venció";

    @Test
    void creaElEnlacePersonalDelUsuarioYLoResuelve() throws Exception {
        HttpClient juan = usuarioConNamespace(SUB_JUAN, EMAIL_JUAN, "juan");
        Instant antes = Instant.now();

        HttpResponse<String> respuesta = crearPersonal(juan,
                "https://docs.ejemplo.com/manual.pdf", "mi-doc");

        assertThat(respuesta.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(respuesta.body());
        assertThat(body.get("alias").asText()).isEqualTo("mi-doc");
        assertThat(body.get("shortUrl").asText())
                .isEqualTo(PUBLIC_BASE + "/juan/mi-doc");
        assertThat(Instant.parse(body.get("expiresAt").asText()))
                .isAfter(antes.plus(Duration.ofMinutes(59)))
                .isBefore(Instant.now().plus(Duration.ofMinutes(61)));
        assertThat(body.get("reuseNotice").asText()).contains("reutilizarse");

        // La asignación pertenece al usuario: guarda su id, el alias
        // personalizado, el destino, la creación y el vencimiento.
        Long usuarioId = idDeUsuario(SUB_JUAN);
        List<Map<String, Object>> filas = db.queryForList(
                "SELECT * FROM asignacion_personal WHERE alias_personalizado = ?", "mi-doc");
        assertThat(filas).hasSize(1);
        Map<String, Object> fila = filas.get(0);
        assertThat(fila.get("usuario_id")).isEqualTo(usuarioId);
        assertThat(fila.get("destino")).isEqualTo("https://docs.ejemplo.com/manual.pdf");
        Instant creada = ((OffsetDateTime) fila.get("creada_en")).toInstant();
        Instant vence = ((OffsetDateTime) fila.get("vence_en")).toInstant();
        assertThat(vence).isEqualTo(creada.plus(Duration.ofMinutes(60)));

        // La resolución redirige al destino con no-store.
        HttpResponse<String> resolucion = get("/juan/mi-doc");
        assertThat(resolucion.statusCode()).isEqualTo(302);
        assertThat(resolucion.headers().firstValue("Location"))
                .contains("https://docs.ejemplo.com/manual.pdf");
        assertThat(resolucion.headers().firstValue("Cache-Control")).contains("no-store");
    }

    @Test
    void normalizaAMinusculasAlCrearYAlResolver() throws Exception {
        HttpClient juan = usuarioConNamespace(SUB_JUAN, EMAIL_JUAN, "juan");

        HttpResponse<String> respuesta = crearPersonal(juan,
                "https://ejemplo.com/mayusculas", "Mi-Doc");

        assertThat(respuesta.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(respuesta.body());
        assertThat(body.get("alias").asText()).isEqualTo("mi-doc");
        assertThat(body.get("shortUrl").asText()).isEqualTo(PUBLIC_BASE + "/juan/mi-doc");
        assertThat(db.queryForObject(
                "SELECT alias_personalizado FROM asignacion_personal"
                        + " WHERE alias_personalizado = 'mi-doc'", String.class))
                .isEqualTo("mi-doc");

        // La resolución tampoco distingue mayúsculas en ningún segmento.
        assertThat(get("/juan/Mi-Doc").statusCode()).isEqualTo(302);
        assertThat(get("/JUAN/mi-doc").statusCode()).isEqualTo(302);
    }

    @Test
    void rechazaLosFormatosInvalidosConCodigoPropio() throws Exception {
        HttpClient juan = usuarioConNamespace(SUB_JUAN, EMAIL_JUAN, "juan");

        List<String> invalidos = List.of(
                "a".repeat(51),
                "con espacio",
                "con_guion_bajo",
                "con.punto",
                "eñe",
                "a/b",
                "?");

        for (String invalido : invalidos) {
            assertError(crearPersonal(juan, "https://ejemplo.com/ok", invalido),
                    400, "ALIAS_INVALID");
        }
        // Ningún rechazo creó una asignación personal.
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM asignacion_personal", Long.class)).isZero();
    }

    @Test
    void aceptaLosLimitesDeUnoYCincuentaCaracteres() throws Exception {
        HttpClient juan = usuarioConNamespace(SUB_JUAN, EMAIL_JUAN, "juan");

        assertThat(crearPersonal(juan, "https://ejemplo.com/uno", "a").statusCode())
                .isEqualTo(201);
        String cincuenta = "a".repeat(49) + "b";
        assertThat(cincuenta).hasSize(50);
        assertThat(crearPersonal(juan, "https://ejemplo.com/cincuenta", cincuenta)
                .statusCode()).isEqualTo(201);
    }

    @Test
    void losDestinosSeValidanConLasReglasDeLaEtapa1() throws Exception {
        HttpClient juan = usuarioConNamespace(SUB_JUAN, EMAIL_JUAN, "juan");

        assertError(crearPersonal(juan, "", "mio"), "EMPTY_DESTINATION");
        assertError(crearPersonal(juan, "ftp://ejemplo.com/x", "mio"), "UNSUPPORTED_SCHEME");
        assertError(crearPersonal(juan, "https://ejemplo.com/con espacios", "mio"),
                "MALFORMED_DESTINATION");
        assertError(crearPersonal(juan, PUBLIC_BASE + "/propio", "mio"), "OWN_ORIGIN");

        // Los rechazos no crearon asignaciones personales.
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM asignacion_personal", Long.class)).isZero();
    }

    @Test
    void conAliasPersonalizadoYSinSesionResponde401() throws Exception {
        HttpClient dispositivo = nuevoClienteDispositivo();

        HttpResponse<String> respuesta = crearPersonal(dispositivo,
                "https://ejemplo.com/x", "mio");

        assertError(respuesta, 401, "NOT_AUTHENTICATED");

        // Sin alias personalizado la creación anónima sigue intacta.
        HttpResponse<String> anonimo = postJson(dispositivo, "/api/links",
                "{\"destination\":\"https://ejemplo.com/anonimo\"}");
        assertThat(anonimo.statusCode()).isEqualTo(201);
    }

    @Test
    void conSesionSinNamespaceRespondeQueHayQueElegirlo() throws Exception {
        HttpClient juan = sesionDe(SUB_JUAN, EMAIL_JUAN);

        HttpResponse<String> respuesta = crearPersonal(juan,
                "https://ejemplo.com/x", "mio");

        assertError(respuesta, 409, "NAMESPACE_NOT_CHOSEN");
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM asignacion_personal", Long.class)).isZero();
    }

    @Test
    void repetirUnAliasPersonalizadoVigenteResponde409() throws Exception {
        HttpClient juan = usuarioConNamespace(SUB_JUAN, EMAIL_JUAN, "juan");
        assertThat(crearPersonal(juan, "https://ejemplo.com/uno", "mio").statusCode())
                .isEqualTo(201);

        HttpResponse<String> repetido = crearPersonal(juan, "https://ejemplo.com/dos", "MIO");

        assertError(repetido, 409, "ALIAS_TAKEN");
        // El rechazo no pisó la asignación vigente: sigue conduciendo al
        // primer destino.
        HttpResponse<String> resolucion = get("/juan/mio");
        assertThat(resolucion.statusCode()).isEqualTo(302);
        assertThat(resolucion.headers().firstValue("Location"))
                .contains("https://ejemplo.com/uno");
    }

    @Test
    void alVencerElMismoUsuarioReusaElAliasYElHistorialConservaAmbas() throws Exception {
        HttpClient juan = usuarioConNamespace(SUB_JUAN, EMAIL_JUAN, "juan");
        assertThat(crearPersonal(juan, "https://ejemplo.com/primero", "mio").statusCode())
                .isEqualTo(201);
        marcarVencidoPersonal("mio");

        HttpResponse<String> segunda = crearPersonal(juan, "https://ejemplo.com/segundo", "mio");

        assertThat(segunda.statusCode()).isEqualTo(201);
        // Las dos asignaciones quedan en el historial (ADR 0003): la
        // vencida y la vigente.
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM asignacion_personal"
                        + " WHERE usuario_id = ? AND alias_personalizado = ?",
                Long.class, idDeUsuario(SUB_JUAN), "mio")).isEqualTo(2);
        // La resolución conduce al nuevo destino.
        HttpResponse<String> resolucion = get("/juan/mio");
        assertThat(resolucion.statusCode()).isEqualTo(302);
        assertThat(resolucion.headers().firstValue("Location"))
                .contains("https://ejemplo.com/segundo");
    }

    @Test
    void otroUsuarioPuedeUsarElMismoAliasEnSuPropioNamespace() throws Exception {
        HttpClient juan = usuarioConNamespace(SUB_JUAN, EMAIL_JUAN, "juan");
        HttpClient pedro = usuarioConNamespace(SUB_PEDRO, EMAIL_PEDRO, "pedro");
        assertThat(crearPersonal(juan, "https://ejemplo.com/de-juan", "compartido")
                .statusCode()).isEqualTo(201);

        HttpResponse<String> dePedro = crearPersonal(pedro,
                "https://ejemplo.com/de-pedro", "compartido");

        assertThat(dePedro.statusCode()).isEqualTo(201);
        assertThat(json.readTree(dePedro.body()).get("shortUrl").asText())
                .isEqualTo(PUBLIC_BASE + "/pedro/compartido");
        // Cada namespace resuelve a su propio destino.
        assertThat(get("/juan/compartido").headers().firstValue("Location"))
                .contains("https://ejemplo.com/de-juan");
        assertThat(get("/pedro/compartido").headers().firstValue("Location"))
                .contains("https://ejemplo.com/de-pedro");
    }

    @Test
    void inexistenteOVencidoResponde404ConLaPaginaAcordada() throws Exception {
        HttpClient juan = usuarioConNamespace(SUB_JUAN, EMAIL_JUAN, "juan");
        assertThat(crearPersonal(juan, "https://ejemplo.com/corto", "mio").statusCode())
                .isEqualTo(201);

        for (String ruta : List.of("/juan/nada", "/nadie/mio", "/juan/otro-alias")) {
            HttpResponse<String> inexistente = get(ruta);
            assertThat(inexistente.statusCode()).as(ruta).isEqualTo(404);
            assertThat(inexistente.body()).contains(MENSAJE_404);
            assertThat(inexistente.headers().firstValue("Cache-Control")).contains("no-store");
        }

        marcarVencidoPersonal("mio");
        HttpResponse<String> vencido = get("/juan/mio");
        assertThat(vencido.statusCode()).isEqualTo(404);
        assertThat(vencido.body()).contains(MENSAJE_404);
        assertThat(vencido.headers().firstValue("Cache-Control")).contains("no-store");
    }

    @Test
    void unSoloSegmentoSigueResolviendoElAliasAunqueCoincidaConUnNamespace()
            throws Exception {
        HttpClient juan = usuarioConNamespace(SUB_JUAN, EMAIL_JUAN, "juan");

        // Sin alias de la etapa 1 con ese texto, /juan es el 404 del
        // alias desconocido: el namespace solo no muestra un perfil.
        HttpResponse<String> sinAlias = get("/juan");
        assertThat(sinAlias.statusCode()).isEqualTo(404);
        assertThat(sinAlias.body()).contains(MENSAJE_404);

        // Con un alias vigente "juan" de la etapa 1, /juan conduce a su
        // destino aunque el texto sea también el namespace del usuario.
        seedAliasVigente("juan", "https://ejemplo.com/alias-etapa-1");
        HttpResponse<String> alias = get("/juan");
        assertThat(alias.statusCode()).isEqualTo(302);
        assertThat(alias.headers().firstValue("Location"))
                .contains("https://ejemplo.com/alias-etapa-1");
    }

    @Test
    void dosCreacionesALaVezDelMismoAliasNoTerminanConDosVigentes() throws Exception {
        // El mismo usuario en dos dispositivos: las dos creaciones se
        // serializan y una sola asignación queda vigente.
        HttpClient dispositivoA = usuarioConNamespace(SUB_JUAN, EMAIL_JUAN, "juan");
        HttpClient dispositivoB = sesionDe(SUB_JUAN, EMAIL_JUAN);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier salida = new CyclicBarrier(2);
        try {
            Callable<HttpResponse<String>> desdeA = () -> {
                salida.await(10, TimeUnit.SECONDS);
                return crearPersonal(dispositivoA, "https://ejemplo.com/carrera", "mismo");
            };
            Callable<HttpResponse<String>> desdeB = () -> {
                salida.await(10, TimeUnit.SECONDS);
                return crearPersonal(dispositivoB, "https://ejemplo.com/carrera", "mismo");
            };
            Future<HttpResponse<String>> primero = pool.submit(desdeA);
            Future<HttpResponse<String>> segundo = pool.submit(desdeB);

            List<HttpResponse<String>> respuestas = List.of(
                    primero.get(30, TimeUnit.SECONDS),
                    segundo.get(30, TimeUnit.SECONDS));

            assertThat(respuestas).extracting(HttpResponse::statusCode)
                    .containsExactlyInAnyOrder(201, 409);
            HttpResponse<String> rechazo = respuestas.stream()
                    .filter(r -> r.statusCode() == 409).findFirst().orElseThrow();
            assertError(rechazo, 409, "ALIAS_TAKEN");
            assertThat(db.queryForObject(
                    "SELECT COUNT(*) FROM asignacion_personal"
                            + " WHERE usuario_id = ? AND alias_personalizado = ?",
                    Long.class, idDeUsuario(SUB_JUAN), "mismo")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void conSesionYElCampoVacioCreaUnEnlaceAcortado() throws Exception {
        HttpClient juan = usuarioConNamespace(SUB_JUAN, EMAIL_JUAN, "juan");

        for (String cuerpo : List.of(
                "{\"destination\":\"https://ejemplo.com/vacio\",\"aliasPersonalizado\":\"\"}",
                "{\"destination\":\"https://ejemplo.com/vacio\"}",
                "{\"destination\":\"https://ejemplo.com/vacio\",\"aliasPersonalizado\":null}")) {
            HttpResponse<String> respuesta = postJson(juan, "/api/links", cuerpo);

            assertThat(respuesta.statusCode()).as(cuerpo).isEqualTo(201);
            JsonNode body = json.readTree(respuesta.body());
            String alias = body.get("alias").asText();
            assertThat(alias).matches("[1-9A-HJ-NP-Za-km-z]{1,32}");
            assertThat(body.get("shortUrl").asText()).isEqualTo(PUBLIC_BASE + "/" + alias);
        }
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM asignacion_personal", Long.class)).isZero();
    }

    @Test
    void laWebIncluyeElCampoDeAliasPersonalizado() throws Exception {
        HttpResponse<String> pagina = get("/");

        assertThat(pagina.statusCode()).isEqualTo(200);
        assertThat(pagina.body()).contains("alias-personalizado");
    }

    // ---- Helpers ----

    /** Inicia sesión con Google simulado y deja la cookie en el cliente. */
    private HttpClient sesionDe(String sub, String email) throws Exception {
        HttpClient dispositivo = nuevoClienteDispositivo();
        GoogleSimulado.login(dispositivo, baseUrl(), sub, email);
        return dispositivo;
    }

    /** Sesión del usuario con su namespace ya elegido. */
    private HttpClient usuarioConNamespace(String sub, String email, String namespace)
            throws Exception {
        HttpClient dispositivo = sesionDe(sub, email);
        HttpResponse<String> alta = postJson(dispositivo, "/api/namespace",
                "{\"namespace\":\"" + namespace + "\"}");
        assertThat(alta.statusCode()).as("alta del namespace %s", namespace).isEqualTo(200);
        return dispositivo;
    }

    private HttpResponse<String> crearPersonal(HttpClient dispositivo, String destino,
                                               String alias) throws Exception {
        return postJson(dispositivo, "/api/links",
                "{\"destination\":\"" + destino + "\",\"aliasPersonalizado\":\""
                        + alias + "\"}");
    }

    private Long idDeUsuario(String sub) {
        return db.queryForObject(
                "SELECT id FROM usuario WHERE google_sub = ?", Long.class, sub);
    }

    /**
     * Marca vencida la asignación personal de ese alias, como si su hora
     * ya hubiera pasado: no hay operación pública que la venza.
     */
    private void marcarVencidoPersonal(String aliasPersonalizado) {
        Instant ahora = Instant.now();
        db.update("UPDATE asignacion_personal SET creada_en = ?, vence_en = ?"
                        + " WHERE alias_personalizado = ?",
                OffsetDateTime.ofInstant(ahora.minus(Duration.ofHours(2)), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(ahora.minus(Duration.ofHours(1)), ZoneOffset.UTC),
                aliasPersonalizado);
    }

    /** Alias de la etapa 1 con su asignación vigente, sembrado por SQL. */
    private void seedAliasVigente(String codigo, String destino) {
        Instant ahora = Instant.now();
        db.update("INSERT INTO alias (codigo) VALUES (?)", codigo);
        Long id = db.queryForObject(
                "INSERT INTO asignacion (alias_codigo, destino, creada_en, vence_en)"
                        + " VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, codigo, destino,
                OffsetDateTime.ofInstant(ahora.minus(Duration.ofMinutes(10)), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(ahora.plus(Duration.ofMinutes(50)), ZoneOffset.UTC));
        db.update("UPDATE alias SET asignacion_actual_id = ? WHERE codigo = ?", id, codigo);
        // Un alias existente cuenta como ya emitido: el generador nunca
        // lo vuelve a entregar (la misma invariante del seed de
        // ConcurrenciaHttpTest).
        db.update("UPDATE generador_alias SET proximo_indice ="
                        + " GREATEST(proximo_indice, ?)",
                CodigosDeAlias.indiceDe(codigo) + 1);
    }
}
