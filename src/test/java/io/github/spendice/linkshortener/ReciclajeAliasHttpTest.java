package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reciclaje y expansión de alias sobre la API HTTP con PostgreSQL real
 * (Testcontainers, ADR 0004) y el reloj de servidor controlable
 * ({@link MutableClock}). Verifica la política de selección completa:
 * preferir alias cuya asignación actual venció, priorizar los de menor
 * longitud con desempate determinista por código, agotar los códigos de
 * una longitud antes de emitir la siguiente, conservar el historial de
 * asignaciones al reasignar y resolver siempre el destino vigente.
 *
 * <p>Cada escenario parte del espacio vacío: la siembra de alias vencidos
 * se hace por SQL para cubrir códigos que el generador todavía no emitió
 * (el reciclaje se deriva de las asignaciones persistidas, no del avance
 * del generador).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {LinkShortenerApplication.class, ClockTestConfiguration.class},
        properties = {
                "shortener.public-base-url=http://192.168.50.10:8080",
                "shortener.reserved-routes=api, error"
        })
@Testcontainers
class ReciclajeAliasHttpTest {

    private static final Instant T0 = Instant.parse("2026-10-05T12:00:00Z");
    private static final Duration DURACION = Duration.ofMinutes(60);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @LocalServerPort
    int port;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate db;
    @Autowired
    MutableClock clock;

    @BeforeEach
    void espacioDeCodigosVacio() {
        // Sin alias, sin historial y con la enumeración del generador en
        // su origen: cada escenario controla el estado desde cero.
        db.execute("TRUNCATE TABLE alias, asignacion RESTART IDENTITY");
        db.update("UPDATE generador_alias SET proximo_indice = 0");
        clock.set(T0);
    }

    @Test
    void reciclaUnAliasVencidoAntesDeGenerarUnCodigoNuevo() throws Exception {
        String alias = createAlias("https://ejemplo.com/viejo");
        assertThat(alias).isEqualTo("1");
        assertThat(proximoIndice()).isEqualTo(1);

        clock.set(T0.plus(DURACION).plusNanos(1));
        String reciclado = createAlias("https://ejemplo.com/nuevo");

        // Reutiliza el vencido: no emite un código nuevo ni consume el
        // avance del generador.
        assertThat(reciclado).isEqualTo(alias);
        assertThat(cantidadAlias()).isEqualTo(1);
        assertThat(proximoIndice()).isEqualTo(1);
    }

    @Test
    void unAliasVigenteNoEsCandidatoAlReciclaje() throws Exception {
        String vigente = createAlias("https://ejemplo.com/vigente");
        assertThat(vigente).isEqualTo("1");

        clock.set(T0.plus(Duration.ofMinutes(30)));
        String nuevo = createAlias("https://ejemplo.com/nuevo");

        assertThat(nuevo).isEqualTo("2");
        assertThat(get("/" + vigente).statusCode()).isEqualTo(302);
    }

    @Test
    void agotaLos58CodigosDeUnCaracterAntesDeEmitirElPrimeroDeDos() throws Exception {
        Set<String> emitidos = new HashSet<>();
        for (int i = 0; i < 58; i++) {
            String alias = createAlias("https://ejemplo.com/espacio/" + i);
            assertThat(alias).hasSize(1);
            emitidos.add(alias);
        }
        assertThat(emitidos).hasSize(58);

        // La longitud mínima disponible avanza: el primer código de dos
        // caracteres es "11" y la enumeración continúa determinista.
        assertThat(createAlias("https://ejemplo.com/espacio/58")).isEqualTo("11");
        assertThat(createAlias("https://ejemplo.com/espacio/59")).isEqualTo("12");
    }

    @Test
    void prefiereLosVencidosDeMenorLongitudYDesempataPorCodigo() throws Exception {
        // Espacio emitido: los 58 de un carácter más "11".
        for (int i = 0; i < 58; i++) {
            createAlias("https://ejemplo.com/lote/" + i);
        }
        assertThat(createAlias("https://ejemplo.com/lote/58")).isEqualTo("11");

        clock.set(T0.plus(DURACION).plusNanos(1));

        // Todos vencidos: se reciclan primero los de un carácter. Entre
        // ellos el orden es el natural del código ("1", "2", ...).
        assertThat(createAlias("https://ejemplo.com/reusa-1")).isEqualTo("1");
        // "11" precede a "2" en orden natural: si el desempate fuera
        // solo lexicográfico ganaría "11". Elegir "2" demuestra que la
        // prioridad es la menor longitud.
        assertThat(createAlias("https://ejemplo.com/reusa-2")).isEqualTo("2");
        assertThat(createAlias("https://ejemplo.com/reusa-3")).isEqualTo("3");
    }

    @Test
    void laReasignacionConservaElHistorialYConduceAlNuevoDestino() throws Exception {
        String alias = createAlias("https://ejemplo.com/destino-viejo?a=1#frag");
        Instant venceViejo = T0.plus(DURACION);
        assertThat(get("/" + alias).headers().firstValue("Location"))
                .contains("https://ejemplo.com/destino-viejo?a=1#frag");

        Instant instanteReuso = venceViejo.plusSeconds(1);
        clock.set(instanteReuso);
        String reciclado = createAlias("https://ejemplo.com/destino-nuevo");
        assertThat(reciclado).isEqualTo(alias);

        // El enlace público — y un QR antiguo del mismo alias — conduce
        // al nuevo destino; la asignación histórica no decide la
        // resolución vigente.
        HttpResponse<String> redirect = get("/" + alias);
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("Location"))
                .contains("https://ejemplo.com/destino-nuevo");

        // Historial intacto: dos asignaciones con identidad propia, cada
        // una con su destino, creación y vencimiento originales.
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT id, destino, creada_en, vence_en FROM asignacion"
                        + " WHERE alias_codigo = ? ORDER BY id", alias);
        assertThat(rows).hasSize(2);
        Map<String, Object> anterior = rows.get(0);
        Map<String, Object> vigente = rows.get(1);
        assertThat(anterior.get("id")).isNotEqualTo(vigente.get("id"));
        assertThat(anterior.get("destino")).isEqualTo("https://ejemplo.com/destino-viejo?a=1#frag");
        assertThat(toInstant(anterior.get("creada_en"))).isEqualTo(T0);
        assertThat(toInstant(anterior.get("vence_en"))).isEqualTo(venceViejo);
        assertThat(vigente.get("destino")).isEqualTo("https://ejemplo.com/destino-nuevo");
        assertThat(toInstant(vigente.get("creada_en"))).isEqualTo(instanteReuso);
        assertThat(toInstant(vigente.get("vence_en"))).isEqualTo(instanteReuso.plus(DURACION));
        assertThat(db.queryForObject(
                "SELECT asignacion_actual_id FROM alias WHERE codigo = ?",
                Long.class, alias)).isEqualTo(vigente.get("id"));
    }

    @Test
    void unAliasReasignadoPuedeVolverAReciclarseSinPerderElHistorial() throws Exception {
        String alias = createAlias("https://ejemplo.com/primero");

        Instant instanteSegunda = T0.plus(DURACION).plusNanos(1);
        clock.set(instanteSegunda);
        assertThat(createAlias("https://ejemplo.com/segundo")).isEqualTo(alias);

        clock.set(instanteSegunda.plus(DURACION).plusNanos(1));
        assertThat(createAlias("https://ejemplo.com/tercero")).isEqualTo(alias);

        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM asignacion WHERE alias_codigo = ?",
                Long.class, alias)).isEqualTo(3);
        assertThat(get("/" + alias).headers().firstValue("Location"))
                .contains("https://ejemplo.com/tercero");
    }

    @Test
    void elReciclajeDistingueMayusculasDeMinusculas() throws Exception {
        // Sembrados por SQL: el reciclaje se deriva de las asignaciones
        // vencidas persistidas, no del avance del generador.
        seedAliasVencido("a", "https://ejemplo.com/minuscula");
        seedAliasVencido("A", "https://ejemplo.com/mayuscula");

        // 'A' precede a 'a' en el orden natural: se recicla primero y
        // cada código conserva su caso, que son alias distintos.
        assertThat(createAlias("https://ejemplo.com/para-A")).isEqualTo("A");
        assertThat(createAlias("https://ejemplo.com/para-a")).isEqualTo("a");

        assertThat(get("/A").headers().firstValue("Location"))
                .contains("https://ejemplo.com/para-A");
        assertThat(get("/a").headers().firstValue("Location"))
                .contains("https://ejemplo.com/para-a");
    }

    @Test
    void creacionesConcurrentesNoDuplicanElMismoAliasVencido() throws Exception {
        seedAliasVencido("5", "https://ejemplo.com/vencido");

        int concurrent = 4;
        var pool = Executors.newFixedThreadPool(concurrent);
        var ready = new CountDownLatch(concurrent);
        var go = new CountDownLatch(1);
        var futures = new ArrayList<Future<Map.Entry<String, HttpResponse<String>>>>();
        for (int i = 0; i < concurrent; i++) {
            String destino = "https://ejemplo.com/hilo-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                // Timeout en todos los puntos de espera: un hilo trabado
                // falla la prueba en lugar de colgar la suite.
                assertThat(go.await(30, TimeUnit.SECONDS)).isTrue();
                return Map.entry(destino,
                        postJson("{\"destination\":\"" + destino + "\"}"));
            }));
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        Map<String, String> destinosPorAlias = new HashMap<>();
        for (Future<Map.Entry<String, HttpResponse<String>>> future : futures) {
            Map.Entry<String, HttpResponse<String>> intento = future.get(60, TimeUnit.SECONDS);
            HttpResponse<String> response = intento.getValue();
            assertThat(response.statusCode()).isEqualTo(201);
            JsonNode body = json.readTree(response.body());
            // Cada creación exitosa tiene duración propia: vence a los
            // 60 minutos del instante de creación T0.
            assertThat(Instant.parse(body.get("expiresAt").asText()))
                    .isEqualTo(T0.plus(DURACION));
            destinosPorAlias.put(body.get("alias").asText(), intento.getKey());
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // La reserva serializada entrega el vencido a una sola creación;
        // las demás emiten códigos nuevos. Todos los alias son exclusivos.
        assertThat(destinosPorAlias.keySet())
                .containsExactlyInAnyOrder("5", "1", "2", "3");
        // La asignación vencida original quedó conservada: son dos, no una,
        // y la referencia actual apunta a la asignación nueva del ganador.
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM asignacion WHERE alias_codigo = ?",
                Long.class, "5")).isEqualTo(2);
        assertThat(db.queryForObject(
                "SELECT a.destino FROM alias al"
                        + " JOIN asignacion a ON a.id = al.asignacion_actual_id"
                        + " AND a.alias_codigo = al.codigo WHERE al.codigo = ?",
                String.class, "5")).isEqualTo(destinosPorAlias.get("5"));
    }

    /** Alias con su asignación actual ya vencida al instante T0. */
    private void seedAliasVencido(String code, String destino) {
        db.update("INSERT INTO alias (codigo) VALUES (?)", code);
        Long id = db.queryForObject(
                "INSERT INTO asignacion (alias_codigo, destino, creada_en, vence_en)"
                        + " VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, code, destino,
                OffsetDateTime.ofInstant(T0.minus(Duration.ofHours(2)), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(T0.minus(Duration.ofHours(1)), ZoneOffset.UTC));
        db.update("UPDATE alias SET asignacion_actual_id = ? WHERE codigo = ?", id, code);
    }

    private long cantidadAlias() {
        return db.queryForObject("SELECT COUNT(*) FROM alias", Long.class);
    }

    private long proximoIndice() {
        return db.queryForObject("SELECT proximo_indice FROM generador_alias WHERE id = 1", Long.class);
    }

    private static Instant toInstant(Object timestamp) {
        if (timestamp instanceof Timestamp ts) {
            return ts.toInstant();
        }
        return ((OffsetDateTime) timestamp).toInstant();
    }

    private String createAlias(String destination) throws Exception {
        HttpResponse<String> response = postJson("{\"destination\":\"" + destination + "\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        return body.get("alias").asText();
    }

    private HttpResponse<String> postJson(String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/links"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(jsonBody))
                .build();
        return client.send(request, BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build();
        return client.send(request, BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }
}
