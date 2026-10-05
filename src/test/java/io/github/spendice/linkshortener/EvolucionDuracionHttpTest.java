package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
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
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Experimento de adaptación del issue #10 sobre el MISMO PostgreSQL
 * (Testcontainers, ADR 0004): un contexto con la duración de entrega
 * ({@code link-duration=PT60M}) crea el grupo A; se cierra y un segundo
 * contexto arranca con el cambio acotado ({@code link-duration=PT5M})
 * para el grupo B. El reloj mutable verifica que cada grupo resuelve y
 * vence según el {@code vence_en} que persistió al crearse: las
 * asignaciones anteriores no se recalculan y la resolución nunca
 * consulta la duración configurada. La duración de entrega queda
 * restituida: el cambio existe solo como argumento de arranque del
 * segundo contexto; {@code application.yml} conserva {@code PT60M}.
 */
@Testcontainers
class EvolucionDuracionHttpTest {

    private static final Instant T0 = Instant.parse("2026-10-05T12:00:00Z");
    private static final Duration DURACION_ENTREGA = Duration.ofMinutes(60);
    private static final Duration DURACION_EXPERIMENTO = Duration.ofMinutes(5);
    private static final String MENSAJE_404 = "Este enlace no existe o venció";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void cambiarLaDuracionNoRecalculaLasAsignacionesExistentes() throws Exception {
        try (Experimento experimento = ejecutarExperimento()) {
            JdbcTemplate db = experimento.contexto().getBean(JdbcTemplate.class);

            // El grupo A conserva creación y vencimiento de 60 minutos:
            // ninguna fila se recalculó al cambiar la duración.
            assertAsignacion(db, experimento.aliasA1(), "https://ejemplo.com/grupo-a1",
                    T0, T0.plus(DURACION_ENTREGA));
            assertAsignacion(db, experimento.aliasA2(), "https://ejemplo.com/grupo-a2",
                    T0.plus(Duration.ofMinutes(1)), T0.plus(Duration.ofMinutes(1)).plus(DURACION_ENTREGA));

            // El grupo B recibió la duración del experimento al crearse.
            assertAsignacion(db, experimento.aliasB1(), "https://ejemplo.com/grupo-b1",
                    T0.plus(Duration.ofMinutes(2)), T0.plus(Duration.ofMinutes(2)).plus(DURACION_EXPERIMENTO));
            assertAsignacion(db, experimento.aliasB2(), "https://ejemplo.com/grupo-b2",
                    T0.plus(Duration.ofMinutes(3)), T0.plus(Duration.ofMinutes(3)).plus(DURACION_EXPERIMENTO));
        }
    }

    @Test
    void cadaGrupoResuelveYVenceEnSuPropioInstantePersistido() throws Exception {
        try (Experimento experimento = ejecutarExperimento()) {
            MutableClock reloj = experimento.contexto().getBean(MutableClock.class);
            int puerto = port(experimento.contexto());

            reloj.set(T0.plus(Duration.ofMinutes(5)));
            assertResuelve(puerto, experimento.aliasA1(), "grupo-a1");
            assertResuelve(puerto, experimento.aliasA2(), "grupo-a2");
            assertResuelve(puerto, experimento.aliasB1(), "grupo-b1");
            assertResuelve(puerto, experimento.aliasB2(), "grupo-b2");

            // Límite exacto de B1 (vence T0+7m): deja de resolver mientras
            // el resto sigue vigente con su propio vencimiento.
            reloj.set(T0.plus(Duration.ofMinutes(7)));
            assertNoResuelve(puerto, experimento.aliasB1());
            assertResuelve(puerto, experimento.aliasB2(), "grupo-b2");
            assertResuelve(puerto, experimento.aliasA1(), "grupo-a1");

            reloj.set(T0.plus(Duration.ofMinutes(8)));
            assertNoResuelve(puerto, experimento.aliasB2());
            assertResuelve(puerto, experimento.aliasA1(), "grupo-a1");
            assertResuelve(puerto, experimento.aliasA2(), "grupo-a2");

            // Límite exacto de A1 (vence T0+60m): A2 conserva un minuto más.
            reloj.set(T0.plus(Duration.ofMinutes(60)));
            assertNoResuelve(puerto, experimento.aliasA1());
            assertResuelve(puerto, experimento.aliasA2(), "grupo-a2");

            reloj.set(T0.plus(Duration.ofMinutes(61)));
            assertNoResuelve(puerto, experimento.aliasA2());
        }
    }

    /**
     * Las dos fases del experimento: crea el grupo A con la duración de
     * entrega, cierra ese contexto (el cambio llega por configuración y
     * rearranque, no por mutación en caliente) y crea el grupo B con la
     * duración acotada. Devuelve el segundo contexto abierto.
     */
    private Experimento ejecutarExperimento() throws Exception {
        String aliasA1;
        String aliasA2;
        ConfigurableApplicationContext entrega = startApp("PT60M");
        try {
            MutableClock reloj = entrega.getBean(MutableClock.class);
            reloj.set(T0);
            aliasA1 = crearEnlace(port(entrega), "https://ejemplo.com/grupo-a1",
                    T0.plus(DURACION_ENTREGA));
            reloj.set(T0.plus(Duration.ofMinutes(1)));
            aliasA2 = crearEnlace(port(entrega), "https://ejemplo.com/grupo-a2",
                    T0.plus(Duration.ofMinutes(1)).plus(DURACION_ENTREGA));

            // El grupo A resolvía con la configuración original.
            assertResuelve(port(entrega), aliasA1, "grupo-a1");
        } finally {
            entrega.close();
        }

        ConfigurableApplicationContext conNuevaDuracion = startApp("PT5M");
        try {
            MutableClock reloj = conNuevaDuracion.getBean(MutableClock.class);
            reloj.set(T0.plus(Duration.ofMinutes(2)));
            String aliasB1 = crearEnlace(port(conNuevaDuracion), "https://ejemplo.com/grupo-b1",
                    T0.plus(Duration.ofMinutes(2)).plus(DURACION_EXPERIMENTO));
            reloj.set(T0.plus(Duration.ofMinutes(3)));
            String aliasB2 = crearEnlace(port(conNuevaDuracion), "https://ejemplo.com/grupo-b2",
                    T0.plus(Duration.ofMinutes(3)).plus(DURACION_EXPERIMENTO));
            return new Experimento(conNuevaDuracion, aliasA1, aliasA2, aliasB1, aliasB2);
        } catch (Exception | Error e) {
            conNuevaDuracion.close();
            throw e;
        }
    }

    private void assertAsignacion(JdbcTemplate db, String alias,
                                  String destino, Instant creadaEn, Instant venceEn) {
        Map<String, Object> row = db.queryForMap(
                "SELECT id, destino FROM asignacion WHERE alias_codigo = ?", alias);
        assertThat(row.get("destino")).isEqualTo(destino);
        Long id = (Long) row.get("id");
        assertThat(db.queryForObject(
                "SELECT asignacion_actual_id FROM alias WHERE codigo = ?",
                Long.class, alias)).isEqualTo(id);
        assertThat(db.queryForObject(
                "SELECT creada_en FROM asignacion WHERE id = ?",
                OffsetDateTime.class, id).toInstant()).isEqualTo(creadaEn);
        assertThat(db.queryForObject(
                "SELECT vence_en FROM asignacion WHERE id = ?",
                OffsetDateTime.class, id).toInstant()).isEqualTo(venceEn);
    }

    private void assertResuelve(int port, String alias, String destinoContiene) throws Exception {
        HttpResponse<String> response = get(port, "/" + alias);
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location"))
                .hasValueSatisfying(location -> assertThat(location).contains(destinoContiene));
    }

    private void assertNoResuelve(int port, String alias) throws Exception {
        HttpResponse<String> response = get(port, "/" + alias);
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains(MENSAJE_404);
    }

    private ConfigurableApplicationContext startApp(String linkDuration) {
        // Las propiedades viajan como argumentos: el experimento cambia
        // solo link-duration, sin tocar archivos ni el contrato.
        return new SpringApplicationBuilder(LinkShortenerApplication.class)
                .sources(ClockTestConfiguration.class)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--shortener.public-base-url=http://192.168.50.10:8080",
                        "--shortener.link-duration=" + linkDuration);
    }

    private int port(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    private String crearEnlace(int port, String destination, Instant expectedExpiresAt) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl(port) + "/api/links"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"destination\":\"" + destination + "\"}"))
                .build();
        HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        assertThat(Instant.parse(body.get("expiresAt").asText())).isEqualTo(expectedExpiresAt);
        return body.get("alias").asText();
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl(port) + path)).GET().build();
        return client.send(request, BodyHandlers.ofString());
    }

    private String baseUrl(int port) {
        return "http://localhost:" + port;
    }

    /** Estado del experimento con el contexto del grupo B abierto. */
    private record Experimento(ConfigurableApplicationContext contexto,
                               String aliasA1, String aliasA2,
                               String aliasB1, String aliasB2) implements AutoCloseable {
        @Override
        public void close() {
            contexto.close();
        }
    }
}
