package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Import({ClockTestConfiguration.class, InspeccionNombreNamespaceConfiguration.class})
class CambioNamespaceHttpTest extends HttpApiFixture {

    private static final Instant T0 = Instant.parse("2026-10-05T12:00:00Z");

    @Autowired
    MutableClock clock;

    @Autowired
    InspeccionNombreNamespace inspeccion;

    @Autowired
    DataSource dataSource;

    @Test
    void cambiaNamespaceReservaCadaNombreYPermiteRecuperarlo() throws Exception {
        clock.set(T0);
        HttpClient juan = usuarioConNamespace("google-sub-gracia-juan", "juan@ejemplo.com", "juan");
        HttpClient pedro = sesionDe("google-sub-gracia-pedro", "pedro@ejemplo.com");

        // juan -> juani a las 12:00: «juan» queda reservado hasta las 13:00
        // y nadie más puede tomarlo mientras tanto.
        assertThat(postNamespace(juan, "juani").statusCode()).isEqualTo(200);
        assertError(postNamespace(pedro, "juan"), 409, "NAMESPACE_TAKEN");

        // juani -> juanito media hora después: «juani» se reserva con su
        // propio fin de gracia sin tocar el de «juan».
        clock.set(T0.plusSeconds(1800));
        assertThat(postNamespace(juan, "juanito").statusCode()).isEqualTo(200);
        JsonNode lista = json.readTree(get(juan, "/api/namespace/anteriores").body())
                .get("namespaces");
        assertThat(lista.size()).isEqualTo(2);
        assertThat(lista.get(0).get("namespace").asText()).isEqualTo("juan");
        assertThat(lista.get(0).get("graceEndsAt").asText())
                .isEqualTo("2026-10-05T13:00:00Z");
        assertThat(lista.get(1).get("namespace").asText()).isEqualTo("juani");
        assertThat(lista.get(1).get("graceEndsAt").asText())
                .isEqualTo("2026-10-05T13:30:00Z");

        // A las 13:00 la gracia de «juan» ya terminó y la de «juani» sigue:
        // Pedro puede tomar el primero y no el segundo.
        clock.set(T0.plusSeconds(3600));
        assertThat(postNamespace(pedro, "juan").statusCode()).isEqualTo(200);
        assertError(postNamespace(pedro, "juani"), 409, "NAMESPACE_TAKEN");

        // Su usuario sí vuelve a «juani» dentro de la gracia, y «juanito»
        // pasa a anterior con una gracia nueva calculada en ese instante.
        assertThat(postNamespace(juan, "juani").statusCode()).isEqualTo(200);
        assertThat(db.queryForList("SELECT namespace FROM namespace_anterior ORDER BY namespace",
                String.class)).containsExactly("juanito");
        JsonNode restantes = json.readTree(get(juan, "/api/namespace/anteriores").body())
                .get("namespaces");
        assertThat(restantes.size()).isEqualTo(1);
        assertThat(restantes.get(0).get("graceEndsAt").asText())
                .isEqualTo("2026-10-05T14:00:00Z");
        assertThat(db.queryForList(
                "SELECT namespace FROM nombre_namespace WHERE grace_ends_at IS NULL"
                        + " ORDER BY namespace",
                String.class)).containsExactly("juan", "juani");
    }

    @Test
    void nombreQuedaLibreAlFinDeLaGraciaYNoCambiaVencimientosPersonales() throws Exception {
        clock.set(T0);
        HttpClient juan = usuarioConNamespace("google-sub-gracia-juan2", "juan2@ejemplo.com", "juan");
        HttpClient pedro = sesionDe("google-sub-gracia-pedro2", "pedro2@ejemplo.com");
        HttpResponse<String> creado = postJson(juan, "/api/links",
                "{\"destination\":\"https://ejemplo.com\",\"aliasPersonalizado\":\"doc\"}");
        assertThat(creado.statusCode()).isEqualTo(201);
        java.sql.Timestamp vencimiento = db.queryForObject(
                "SELECT vence_en FROM asignacion_personal WHERE alias_personalizado = 'doc'",
                java.sql.Timestamp.class);

        assertThat(postNamespace(juan, "juani").statusCode()).isEqualTo(200);
        HttpResponse<String> enlaceBajoNuevoNombre = get(client, "/juani/doc");
        assertThat(enlaceBajoNuevoNombre.statusCode()).isEqualTo(302);
        assertThat(enlaceBajoNuevoNombre.headers().firstValue("Location").orElseThrow())
                .isEqualTo("https://ejemplo.com");
        clock.set(T0.plusSeconds(3600));
        assertThat(postNamespace(pedro, "juan").statusCode()).isEqualTo(200);
        assertThat(db.queryForObject(
                "SELECT vence_en FROM asignacion_personal WHERE alias_personalizado = 'doc'",
                java.sql.Timestamp.class)).isEqualTo(vencimiento);
    }

    /**
     * La carrera que la clave primaria de {@code nombre_namespace} tiene
     * que serializar: la barrera del inspector detiene a cada transacción
     * en su {@code insert}, así que las dos quedan abiertas dentro de la
     * sección crítica — cada una verificó «libre» disponible mientras la
     * otra seguía sin insertar — y chocan en la PK en vez de resolverse
     * en secuencia por suerte del scheduler. Gana una; la otra recibe
     * {@code NAMESPACE_TAKEN}.
     */
    @Test
    void dosUsuariosQuePidenALaVezUnNamespaceLibreNoLoComparten() throws Exception {
        HttpClient uno = sesionDe("google-sub-gracia-race-uno", "race-uno@ejemplo.com");
        HttpClient dos = sesionDe("google-sub-gracia-race-dos", "race-dos@ejemplo.com");
        var pool = Executors.newFixedThreadPool(2);
        inspeccion.armar(new CyclicBarrier(2));
        try {
            var primera = pool.submit(() -> postNamespace(uno, "libre"));
            var segunda = pool.submit(() -> postNamespace(dos, "libre"));
            HttpResponse<String> respuestaUno = primera.get(20, TimeUnit.SECONDS);
            HttpResponse<String> respuestaDos = segunda.get(20, TimeUnit.SECONDS);
            assertThat(List.of(respuestaUno.statusCode(), respuestaDos.statusCode()))
                    .containsExactlyInAnyOrder(200, 409);
            HttpResponse<String> perdedora =
                    respuestaUno.statusCode() == 409 ? respuestaUno : respuestaDos;
            assertError(perdedora, 409, "NAMESPACE_TAKEN");
            assertThat(db.queryForObject(
                    "SELECT COUNT(*) FROM nombre_namespace WHERE namespace = 'libre'", Long.class))
                    .isEqualTo(1);
        } finally {
            inspeccion.desarmar();
            pool.shutdownNow();
        }
    }

    /**
     * La carrera que el registro de nombres tiene que serializar: Juan
     * suelta «medio» — adoptado por un cambio, no por la primera
     * elección — al mismo tiempo que Pedro intenta tomarlo. Pedro no
     * puede ganarlo ni antes del commit de Juan («medio» sigue siendo el
     * actual de Juan) ni después («medio» quedó reservado como anterior
     * en gracia).
     */
    @Test
    void elNombreQueDejaUnCambioSigueReservadoAnteUnaAdopcionConcurrente() throws Exception {
        clock.set(T0);
        HttpClient juan = usuarioConNamespace("google-sub-gracia-carrera-juan",
                "carrera-juan@ejemplo.com", "juan-inicial");
        HttpClient pedro = usuarioConNamespace("google-sub-gracia-carrera-pedro",
                "carrera-pedro@ejemplo.com", "pedro-inicial");
        assertThat(postNamespace(juan, "medio").statusCode()).isEqualTo(200);

        var barrera = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var suelta = pool.submit(() -> {
                barrera.await(10, TimeUnit.SECONDS);
                return postNamespace(juan, "juan-final");
            });
            var intenta = pool.submit(() -> {
                barrera.await(10, TimeUnit.SECONDS);
                return postNamespace(pedro, "medio");
            });
            HttpResponse<String> deJuan = suelta.get(20, TimeUnit.SECONDS);
            HttpResponse<String> dePedro = intenta.get(20, TimeUnit.SECONDS);
            assertThat(deJuan.statusCode()).isEqualTo(200);
            assertError(dePedro, 409, "NAMESPACE_TAKEN");
        } finally {
            pool.shutdownNow();
        }
        assertThat(db.queryForObject(
                "SELECT namespace FROM usuario WHERE google_sub = 'google-sub-gracia-carrera-pedro'",
                String.class)).isEqualTo("pedro-inicial");
        assertThat(db.queryForObject(
                "SELECT u.google_sub FROM nombre_namespace n JOIN usuario u ON u.id = n.usuario_id"
                        + " WHERE n.namespace = 'medio' AND n.grace_ends_at IS NOT NULL",
                String.class)).isEqualTo("google-sub-gracia-carrera-juan");
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM nombre_namespace WHERE namespace = 'medio'", Long.class))
                .isEqualTo(1);
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM namespace_anterior WHERE namespace = 'medio'"
                        + " AND grace_ends_at = TIMESTAMPTZ '2026-10-05T13:00:00Z'",
                Long.class)).isEqualTo(1);
    }

    /**
     * La adopción tiene que esperar el bloqueo de la fila del namespace:
     * Juan deja «medio» en gracia, otra transacción retiene esa fila con
     * {@code FOR UPDATE} y Pedro intenta tomarla. Sin el
     * {@code PESSIMISTIC_WRITE} del service, Pedro leería la reserva sin
     * esperar y resolvería en el acto; con el bloqueo queda pendiente
     * hasta que la transacción lo suelta, y entonces recibe
     * {@code NAMESPACE_TAKEN}. La reserva de Juan sigue intacta.
     */
    @Test
    void laAdopcionEsperaElBloqueoDelNamespaceAntesDeDecidir() throws Exception {
        clock.set(T0);
        HttpClient juan = usuarioConNamespace("google-sub-gracia-adopcion-juan",
                "adopcion-juan@ejemplo.com", "juan-inicial");
        HttpClient pedro = usuarioConNamespace("google-sub-gracia-adopcion-pedro",
                "adopcion-pedro@ejemplo.com", "pedro-inicial");
        assertThat(postNamespace(juan, "medio").statusCode()).isEqualTo(200);
        assertThat(postNamespace(juan, "juan-final").statusCode()).isEqualTo(200);
        Timestamp finGracia = db.queryForObject(
                "SELECT grace_ends_at FROM namespace_anterior WHERE namespace = 'medio'",
                Timestamp.class);

        var pool = Executors.newFixedThreadPool(1);
        try (Connection bloqueo = dataSource.getConnection()) {
            bloqueo.setAutoCommit(false);
            try (Statement sentencia = bloqueo.createStatement();
                 ResultSet fila = sentencia.executeQuery(
                         "SELECT namespace FROM nombre_namespace"
                                 + " WHERE namespace = 'medio' FOR UPDATE")) {
                assertThat(fila.next()).isTrue();
            }
            var intentoPedro = pool.submit(() -> postNamespace(pedro, "medio"));
            Thread.sleep(500);
            assertThat(intentoPedro.isDone()).isFalse();
            bloqueo.commit();
            assertError(intentoPedro.get(20, TimeUnit.SECONDS), 409, "NAMESPACE_TAKEN");
        } finally {
            pool.shutdownNow();
        }
        assertThat(db.queryForObject(
                "SELECT namespace FROM usuario WHERE google_sub = 'google-sub-gracia-adopcion-pedro'",
                String.class)).isEqualTo("pedro-inicial");
        assertThat(db.queryForObject(
                "SELECT u.google_sub FROM nombre_namespace n JOIN usuario u ON u.id = n.usuario_id"
                        + " WHERE n.namespace = 'medio' AND n.grace_ends_at = ?",
                String.class, finGracia)).isEqualTo("google-sub-gracia-adopcion-juan");
    }

    @Test
    void cambioAplicaFormatoYNombresReservados() throws Exception {
        HttpClient juan = usuarioConNamespace("google-sub-gracia-validacion", "valida@ejemplo.com", "juan");
        assertError(postNamespace(juan, "-mal"), 400, "NAMESPACE_INVALID");
        assertError(postNamespace(juan, "api"), 400, "NAMESPACE_RESERVED");
        assertThat(db.queryForObject(
                "SELECT namespace FROM usuario WHERE google_sub = 'google-sub-gracia-validacion'",
                String.class)).isEqualTo("juan");
    }

    /**
     * El listado de anteriores es parte de la sesión del usuario: sin
     * sesión responde {@code 401} con el formato de error del contrato.
     */
    @Test
    void anterioresSinSesionResponde401() throws Exception {
        assertError(get(nuevoClienteDispositivo(), "/api/namespace/anteriores"),
                401, "NOT_AUTHENTICATED");
    }

    /**
     * El fin de gracia se calcula una sola vez, al hacer el cambio
     * (issue #24): un segundo contexto sobre la misma base — la maniobra
     * de {@link SesionReinicioHttpTest} — levantado con otro
     * {@code cuenta.namespace-grace-period} no altera la reserva en
     * curso, y el cambio que él hace sí usa el período nuevo. El reloj
     * se iguala al real porque el segundo contexto no conoce el
     * {@link MutableClock} de la suite.
     */
    @Test
    void cambiarLaConfiguracionNoAlteraLasGraciasEnCurso() throws Exception {
        clock.set(Instant.now());
        HttpClient juan = usuarioConNamespace("google-sub-gracia-config", "config@ejemplo.com", "juan");
        assertThat(postNamespace(juan, "juani").statusCode()).isEqualTo(200);
        Timestamp finOriginal = db.queryForObject(
                "SELECT grace_ends_at FROM namespace_anterior WHERE namespace = 'juan'",
                Timestamp.class);
        assertThat(finOriginal).isNotNull();

        ConfigurableApplicationContext otra =
                startAppCon("--cuenta.namespace-grace-period=PT6H");
        try {
            String base = "http://localhost:"
                    + ((WebServerApplicationContext) otra).getWebServer().getPort();
            JsonNode anteriores = json.readTree(
                    getEn(juan, base, "/api/namespace/anteriores").body())
                    .get("namespaces");
            assertThat(anteriores.size()).isEqualTo(1);
            assertThat(anteriores.get(0).get("namespace").asText()).isEqualTo("juan");
            assertThat(Instant.parse(anteriores.get(0).get("graceEndsAt").asText()))
                    .isEqualTo(finOriginal.toInstant());

            assertThat(postNamespace(juan, base, "juanito").statusCode()).isEqualTo(200);
            assertThat(db.queryForObject(
                    "SELECT grace_ends_at FROM namespace_anterior WHERE namespace = 'juan'",
                    Timestamp.class)).isEqualTo(finOriginal);
            Timestamp finJuani = db.queryForObject(
                    "SELECT grace_ends_at FROM namespace_anterior WHERE namespace = 'juani'",
                    Timestamp.class);
            // Con PT6H el fin nuevo cae unas cinco horas después del que
            // quedó calculado con el default PT1H.
            assertThat(finJuani).isNotNull();
            assertThat(Duration.between(finOriginal.toInstant(), finJuani.toInstant()))
                    .isGreaterThan(Duration.ofHours(4));
        } finally {
            otra.close();
        }
    }

    /** Segundo contexto sobre la misma base, como en SesionReinicioHttpTest. */
    private ConfigurableApplicationContext startAppCon(String... extraArgs) {
        String[] fijos = {
                "--server.port=0",
                "--spring.datasource.url=" + TestHost.jdbcUrl(POSTGRES),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--shortener.public-base-url=" + PUBLIC_BASE,
                "--spring.security.oauth2.client.provider.google.issuer-uri="
                        + GoogleSimulado.issuerUri(),
                "--spring.security.oauth2.client.registration.google.client-id="
                        + GoogleSimulado.CLIENT_ID,
                "--spring.security.oauth2.client.registration.google.client-secret="
                        + GoogleSimulado.CLIENT_SECRET,
        };
        String[] args = new String[fijos.length + extraArgs.length];
        System.arraycopy(fijos, 0, args, 0, fijos.length);
        System.arraycopy(extraArgs, 0, args, fijos.length, extraArgs.length);
        return new SpringApplicationBuilder(LinkShortenerApplication.class).run(args);
    }

    private HttpResponse<String> getEn(HttpClient cliente, String base, String path)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .GET().timeout(Duration.ofSeconds(15)).build();
        return cliente.send(request, BodyHandlers.ofString());
    }

    private HttpResponse<String> postNamespace(HttpClient cliente, String base, String namespace)
            throws Exception {
        String csrf = Csrf.obtener(cliente, base);
        HttpRequest request = Csrf.aplicar(cliente,
                        HttpRequest.newBuilder(URI.create(base + "/api/namespace")), csrf)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(BodyPublishers.ofString("{\"namespace\":\"" + namespace + "\"}"))
                .build();
        return cliente.send(request, BodyHandlers.ofString());
    }

}
