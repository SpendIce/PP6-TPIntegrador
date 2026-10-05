package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.spendice.linkshortener.domain.AliasSequence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrencia sobre la API HTTP con PostgreSQL real (Testcontainers,
 * ADR 0004) — evidencia del issue #6: competición por el mismo alias
 * vencido, prioridad de menor longitud sin saltos por carrera,
 * atomicidad de reserva + asignación + referencia ante fallos,
 * resolución coherente durante una reasignación y reintento sin
 * idempotencia.
 *
 * <p>Las comprobaciones observan respuestas HTTP y el estado persistido
 * consultado por SQL: no dependen del mecanismo concreto de bloqueo ni
 * de detalles del ORM. Todo latch y {@code future.get} usa timeout: un
 * hilo trabado falla la prueba en lugar de colgar la suite.</p>
 */
class ConcurrenciaHttpTest extends HttpApiFixture {

    private static final Duration DURACION = Duration.ofMinutes(60);
    private static final Duration ESPERA_HILOS = Duration.ofSeconds(30);

    @Autowired
    AliasSequence sequence;

    @Test
    void competicionPorUnAliasVencidoLoObtieneExactamenteUnaCreacion() throws Exception {
        // Un único candidato vencido y seis creaciones simultáneas.
        String vencido = seedAliasVencido(0, "https://ejemplo.com/viejo");
        assertThat(vencido).isEqualTo("1");
        long indiceAntes = proximoIndice();

        List<Creacion> creaciones = enParalelo(IntStream.range(0, 6)
                .mapToObj(i -> (Callable<Creacion>) () -> crear("https://ejemplo.com/hilo-" + i))
                .toList());

        // Todas exitosas, cada una con identidad y duración propias: la
        // respuesta corresponde a una asignación persistida del destino
        // pedido, con el vencimiento que el contrato devolvió.
        Set<String> aliases = new HashSet<>();
        Set<Long> ids = new HashSet<>();
        for (Creacion c : creaciones) {
            aliases.add(c.alias());
            ids.add(assertAsignacionPropia(c));
        }
        // Exactamente una obtuvo el vencido; el resto emitió los
        // siguientes códigos nuevos de un carácter, sin duplicar.
        assertThat(aliases).containsExactlyInAnyOrder("1", "2", "3", "4", "5", "6");
        assertThat(ids).hasSize(6);
        assertThat(proximoIndice()).isEqualTo(indiceAntes + 5);

        // El alias reciclado conserva la asignación anterior: dos
        // registros y la referencia actual apunta a la nueva.
        String destinoGanador = creaciones.stream()
                .filter(c -> c.alias().equals(vencido)).findFirst().orElseThrow().destino();
        assertThat(idsDeAsignaciones(vencido)).hasSize(2);
        assertThat(destinoActual(vencido)).isEqualTo(destinoGanador);
        assertEstadoPersistidoConsistente();
    }

    @Test
    void laCargaConcurrenteReparteTodosLosVencidosSinEmitirCodigosNuevos() throws Exception {
        // Seis vencidos de un carácter y seis creaciones simultáneas:
        // ninguna puede emitir un código nuevo mientras queden vencidos.
        List<String> sembrados = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            sembrados.add(seedAliasVencido(i, "https://ejemplo.com/viejo-" + i));
        }
        long indiceAntes = proximoIndice();

        List<Creacion> creaciones = enParalelo(IntStream.range(0, 6)
                .mapToObj(i -> (Callable<Creacion>) () -> crear("https://ejemplo.com/nuevo-" + i))
                .toList());

        assertThat(creaciones.stream().map(Creacion::alias).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(sembrados);
        for (Creacion c : creaciones) {
            assertAsignacionPropia(c);
        }
        // El generador no emitió nada: cada vencido ganó una segunda
        // asignación conservando la anterior.
        assertThat(proximoIndice()).isEqualTo(indiceAntes);
        for (String alias : sembrados) {
            assertThat(idsDeAsignaciones(alias)).hasSize(2);
        }
        assertEstadoPersistidoConsistente();
    }

    @Test
    void laExpansionConcurrenteAgotaLosVencidosCortosAntesDeEmitirMasLargos() throws Exception {
        // Los 58 códigos de un carácter existen vigentes; tres están
        // vencidos. Cinco creaciones simultáneas deben reciclar los tres
        // vencidos cortos antes de emitir los primeros códigos de dos.
        String vigente = null;
        for (int i = 0; i < 58; i++) {
            String sembrado = seedAliasVigente(i, "https://ejemplo.com/vigente-" + i);
            if (i == 10) {
                vigente = sembrado;
            }
        }
        for (int i = 0; i < 3; i++) {
            marcarVencido(sequence.codeAt(i));
        }
        assertThat(proximoIndice()).isEqualTo(58);

        List<Creacion> creaciones = enParalelo(IntStream.range(0, 5)
                .mapToObj(i -> (Callable<Creacion>) () -> crear("https://ejemplo.com/nuevo-" + i))
                .toList());

        // Sin saltos: los tres vencidos se repartieron y recién después
        // se emitieron los dos primeros códigos de dos caracteres.
        assertThat(creaciones.stream().map(Creacion::alias).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("1", "2", "3", "11", "12");
        assertThat(proximoIndice()).isEqualTo(60);
        for (Creacion c : creaciones) {
            assertAsignacionPropia(c);
        }

        // Las asignaciones vigentes no se tocaron: el alias vigente
        // sigue conduciendo a su destino con una sola asignación.
        HttpResponse<String> redirect = get("/" + vigente);
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("Location"))
                .contains("https://ejemplo.com/vigente-10");
        assertThat(idsDeAsignaciones(vigente)).hasSize(1);
        assertEstadoPersistidoConsistente();
    }

    @Test
    void unaCreacionRechazadaNoDejaReservaAsignacionNiReferenciaParciales() throws Exception {
        // Tormenta mixta: creaciones válidas y rechazadas a la vez. La
        // validación corre antes de la reserva dentro de la misma
        // transacción, así que un fallo no puede dejar un alias reservado
        // sin asignación ni una referencia actual colgada.
        List<Callable<Intento>> tareas = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int n = i;
            tareas.add(() -> Intento.valida(crear("https://ejemplo.com/valida-" + n)));
        }
        tareas.add(() -> Intento.rechazo(postJson("{\"destination\":\"\"}"),
                "EMPTY_DESTINATION"));
        tareas.add(() -> Intento.rechazo(postJson("{\"destination\":\"ftp://ejemplo.com/x\"}"),
                "UNSUPPORTED_SCHEME"));
        tareas.add(() -> Intento.rechazo(
                postJson("{\"destination\":\"https://ejemplo.com/con espacios\"}"),
                "MALFORMED_DESTINATION"));
        tareas.add(() -> Intento.rechazo(postJson("esto no es json"), "INVALID_REQUEST"));
        tareas.add(() -> Intento.rechazo(
                postJson("{\"destination\":\"" + PUBLIC_BASE + "/propio\"}"), "OWN_ORIGIN"));

        List<Intento> intentos = enParalelo(tareas);

        for (Intento intento : intentos) {
            if (intento.creacion() != null) {
                assertAsignacionPropia(intento.creacion());
            } else {
                assertError(intento.respuesta(), intento.codigoError());
            }
        }

        // Solo las cinco válidas persistieron: el rechazo no consumió
        // alias ni avance del generador aun compitiendo con válidas.
        assertThat(cantidadAsignaciones()).isEqualTo(5);
        assertThat(cantidadAlias()).isEqualTo(5);
        assertThat(proximoIndice()).isEqualTo(5);
        assertEstadoPersistidoConsistente();
    }

    @Test
    void unaResolucionDuranteLaReasignacionLeeSiempreLaMismaAsignacion() throws Exception {
        // Cinco alias vencidos a punto de reciclarse; un resolvedor por
        // alias consulta en bucle mientras cinco creaciones los
        // reasignan. Cada respuesta debe corresponder a UNA asignación
        // completa: la anterior (vencida, 404) o la nueva (302 al nuevo
        // destino). Mezclar destino viejo con vigencia nueva mostraría
        // un 302 al destino anterior, que nunca puede aparecer.
        int cantidad = 5;
        List<String> aliasVencidos = new ArrayList<>();
        for (int i = 0; i < cantidad; i++) {
            aliasVencidos.add(seedAliasVencido(i, "https://ejemplo.com/viejo-" + i));
        }

        Map<String, Queue<Observacion>> observaciones = new ConcurrentHashMap<>();
        var seguirResolviendo = new AtomicBoolean(true);
        var listos = new CountDownLatch(cantidad * 2);
        var largada = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(cantidad * 2);
        var resolvedores = new ArrayList<Future<?>>();
        var creadores = new ArrayList<Future<HttpResponse<String>>>();
        try {
            for (String alias : aliasVencidos) {
                observaciones.put(alias, new ConcurrentLinkedQueue<>());
                resolvedores.add(pool.submit(() -> {
                    listos.countDown();
                    esperarLargada(largada);
                    while (seguirResolviendo.get()) {
                        HttpResponse<String> r = get("/" + alias);
                        observaciones.get(alias).add(new Observacion(r.statusCode(),
                                r.headers().firstValue("Location").orElse(null)));
                    }
                    return null;
                }));
            }
            for (int i = 0; i < cantidad; i++) {
                int n = i;
                creadores.add(pool.submit(() -> {
                    listos.countDown();
                    esperarLargada(largada);
                    return postJson("{\"destination\":\"https://ejemplo.com/nuevo-" + n + "\"}");
                }));
            }

            assertThat(listos.await(ESPERA_HILOS.toSeconds(), TimeUnit.SECONDS))
                    .as("todos los hilos listos para largar").isTrue();
            largada.countDown();
            for (Future<HttpResponse<String>> f : creadores) {
                assertThat(f.get(ESPERA_HILOS.toSeconds() * 2, TimeUnit.SECONDS).statusCode())
                        .isEqualTo(201);
            }
            seguirResolviendo.set(false);
            for (Future<?> f : resolvedores) {
                f.get(ESPERA_HILOS.toSeconds(), TimeUnit.SECONDS);
            }
        } finally {
            seguirResolviendo.set(false);
            pool.shutdownNow();
        }

        for (String alias : aliasVencidos) {
            String destinoVigente = destinoActual(alias);
            assertThat(destinoVigente).startsWith("https://ejemplo.com/nuevo-");
            boolean vioReasignado = false;
            for (Observacion obs : observaciones.get(alias)) {
                if (obs.status() == 404) {
                    // La asignación anterior, completa y vencida. Una
                    // lectura nunca retrocede: después de ver la nueva
                    // asignación no puede reaparecer la anterior.
                    assertThat(vioReasignado)
                            .as("resolución de %s retrocedió tras la reasignación", alias)
                            .isFalse();
                } else {
                    // La asignación nueva, completa: destino y
                    // vencimiento leídos de la misma fila.
                    assertThat(obs.status()).isEqualTo(302);
                    assertThat(obs.location()).isEqualTo(destinoVigente);
                    vioReasignado = true;
                }
            }
            // Estado final: el alias conduce al destino de su asignación
            // vigente, el destino viejo quedó solo en el historial.
            HttpResponse<String> fin = get("/" + alias);
            assertThat(fin.statusCode()).isEqualTo(302);
            assertThat(fin.headers().firstValue("Location")).contains(destinoVigente);
            assertThat(idsDeAsignaciones(alias)).hasSize(2);
        }
        assertEstadoPersistidoConsistente();
    }

    @Test
    void reintentarLaCreacionTrasPerderLaRespuestaProduceOtraAsignacion() throws Exception {
        // Si la respuesta se pierde, el cliente repite la solicitud: el
        // contrato no promete idempotencia ni deduplicación, y la segunda
        // creación produce otra asignación con identidad y duración
        // propias (user story 22 del issue #1).
        String destino = "https://ejemplo.com/reintento";

        Creacion primera = crear(destino);
        Creacion segunda = crear(destino);

        assertThat(segunda.alias()).isNotEqualTo(primera.alias());
        assertThat(segunda.shortUrl()).isEqualTo(PUBLIC_BASE + "/" + segunda.alias());

        // Dos asignaciones independientes del mismo destino, cada una
        // referenciada como actual de su propio alias.
        assertThat(assertAsignacionPropia(primera)).isNotEqualTo(assertAsignacionPropia(segunda));
        assertThat(cantidadAsignaciones()).isEqualTo(2);
        assertEstadoPersistidoConsistente();
    }

    // ---- Escenarios de siembra y ejecución concurrente ----

    private record Creacion(String destino, String alias, String shortUrl, Instant expiresAt) {
    }

    private record Observacion(int status, String location) {
    }

    private record Intento(Creacion creacion, HttpResponse<String> respuesta, String codigoError) {
        static Intento valida(Creacion creacion) {
            return new Intento(creacion, null, null);
        }

        static Intento rechazo(HttpResponse<String> respuesta, String codigoError) {
            return new Intento(null, respuesta, codigoError);
        }
    }

    /**
     * Ejecuta las tareas largándolas a la vez (latch común) y devuelve
     * sus resultados. Todos los tiempos de espera tienen timeout.
     */
    private <T> List<T> enParalelo(List<? extends Callable<T>> tareas) throws Exception {
        var pool = Executors.newFixedThreadPool(tareas.size());
        try {
            var listos = new CountDownLatch(tareas.size());
            var largada = new CountDownLatch(1);
            var futures = new ArrayList<Future<T>>(tareas.size());
            for (Callable<T> tarea : tareas) {
                futures.add(pool.submit(() -> {
                    listos.countDown();
                    esperarLargada(largada);
                    return tarea.call();
                }));
            }
            assertThat(listos.await(ESPERA_HILOS.toSeconds(), TimeUnit.SECONDS))
                    .as("todos los hilos listos para largar").isTrue();
            largada.countDown();
            var resultados = new ArrayList<T>(tareas.size());
            for (Future<T> future : futures) {
                resultados.add(future.get(ESPERA_HILOS.toSeconds() * 2, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void esperarLargada(CountDownLatch largada) throws InterruptedException {
        assertThat(largada.await(ESPERA_HILOS.toSeconds(), TimeUnit.SECONDS))
                .as("largada sincronizada").isTrue();
    }

    // ---- Helpers de API ----

    private Creacion crear(String destino) throws Exception {
        HttpResponse<String> response = postJson("{\"destination\":\"" + destino + "\"}");
        assertThat(response.statusCode()).as("creación de %s", destino).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        return new Creacion(destino, body.get("alias").asText(), body.get("shortUrl").asText(),
                Instant.parse(body.get("expiresAt").asText()));
    }

    // ---- Helpers de siembra y verificación de persistencia ----

    /**
     * Alias con su asignación actual vigente (vence en dos horas).
     * Sembrar por índice de la enumeración mantiene al generador
     * consistente: un alias existente implica que ya fue emitido.
     */
    private String seedAliasVigente(int indiceEnumeracion, String destino) {
        Instant ahora = Instant.now();
        return seedAlias(indiceEnumeracion, destino, ahora.minus(DURACION), ahora.plus(Duration.ofHours(2)));
    }

    /** Alias con su asignación actual ya vencida: candidato a reciclaje. */
    private String seedAliasVencido(int indiceEnumeracion, String destino) {
        Instant ahora = Instant.now();
        return seedAlias(indiceEnumeracion, destino,
                ahora.minus(Duration.ofHours(2)), ahora.minus(Duration.ofHours(1)));
    }

    private String seedAlias(int indiceEnumeracion, String destino, Instant creadaEn, Instant venceEn) {
        String codigo = sequence.codeAt(indiceEnumeracion);
        db.update("INSERT INTO alias (codigo) VALUES (?)", codigo);
        Long id = db.queryForObject(
                "INSERT INTO asignacion (alias_codigo, destino, creada_en, vence_en)"
                        + " VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, codigo, destino,
                OffsetDateTime.ofInstant(creadaEn, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(venceEn, ZoneOffset.UTC));
        db.update("UPDATE alias SET asignacion_actual_id = ? WHERE codigo = ?", id, codigo);
        db.update("UPDATE generador_alias SET proximo_indice = GREATEST(proximo_indice, ?)",
                indiceEnumeracion + 1L);
        return codigo;
    }

    /** Marca vencida la asignación actual del alias, como si su hora hubiera pasado. */
    private void marcarVencido(String codigo) {
        Instant ahora = Instant.now();
        db.update("UPDATE asignacion SET creada_en = ?, vence_en = ? WHERE alias_codigo = ?",
                OffsetDateTime.ofInstant(ahora.minus(Duration.ofHours(2)), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(ahora.minus(Duration.ofHours(1)), ZoneOffset.UTC),
                codigo);
    }

    /**
     * La creación persistió una asignación propia: el alias emitido y el
     * destino pedido, con duración propia de exactamente 60 minutos
     * ({@code vence_en = creada_en + 60m} en la fila) y el vencimiento
     * que el contrato devolvió. Devuelve su identificador.
     */
    private long assertAsignacionPropia(Creacion creacion) {
        assertThat(creacion.shortUrl()).isEqualTo(PUBLIC_BASE + "/" + creacion.alias());
        List<Map<String, Object>> filas = db.queryForList(
                "SELECT id, creada_en, vence_en FROM asignacion"
                        + " WHERE alias_codigo = ? AND destino = ?",
                creacion.alias(), creacion.destino());
        assertThat(filas).as("asignación propia del alias %s", creacion.alias()).hasSize(1);
        Map<String, Object> fila = filas.get(0);
        Instant creada = toInstant(fila.get("creada_en"));
        Instant vence = toInstant(fila.get("vence_en"));
        // La duración es exacta en la fila persistida (ambas marcas se
        // guardan con la misma precisión, sin sub-microsegundos que
        // difieran entre ellas).
        assertThat(vence).isEqualTo(creada.plus(DURACION));
        // El contrato devolvió el mismo vencimiento que quedó persistido
        // (TIMESTAMPTZ redondea a microsegundos el instante del reloj).
        assertThat(Duration.between(vence, creacion.expiresAt()).abs())
                .as("expiresAt del contrato vs vence_en persistido")
                .isLessThanOrEqualTo(Duration.ofMillis(1));
        return (Long) fila.get("id");
    }

    /**
     * Invariantes persistidas después de la carga: ningún alias quedó
     * reservado sin su asignación actual y toda referencia actual
     * pertenece a una asignación del mismo alias.
     */
    private void assertEstadoPersistidoConsistente() {
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM alias WHERE asignacion_actual_id IS NULL", Long.class))
                .as("alias reservados sin asignación actual").isZero();
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM alias al WHERE NOT EXISTS ("
                        + "SELECT 1 FROM asignacion a WHERE a.id = al.asignacion_actual_id"
                        + " AND a.alias_codigo = al.codigo)", Long.class))
                .as("referencias actuales a asignaciones de otro alias").isZero();
    }

    private long proximoIndice() {
        return db.queryForObject(
                "SELECT proximo_indice FROM generador_alias WHERE id = 1", Long.class);
    }

    private long cantidadAlias() {
        return db.queryForObject("SELECT COUNT(*) FROM alias", Long.class);
    }

    private long cantidadAsignaciones() {
        return db.queryForObject("SELECT COUNT(*) FROM asignacion", Long.class);
    }

    private List<Long> idsDeAsignaciones(String alias) {
        return db.queryForList(
                "SELECT id FROM asignacion WHERE alias_codigo = ?", Long.class, alias);
    }

    private String destinoActual(String alias) {
        return db.queryForObject(
                "SELECT a.destino FROM alias al"
                        + " JOIN asignacion a ON a.id = al.asignacion_actual_id"
                        + " AND a.alias_codigo = al.codigo WHERE al.codigo = ?",
                String.class, alias);
    }

    private static Instant toInstant(Object timestamp) {
        if (timestamp instanceof Timestamp ts) {
            return ts.toInstant();
        }
        return ((OffsetDateTime) timestamp).toInstant();
    }
}
