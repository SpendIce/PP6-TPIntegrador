package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vencimiento sobre la API HTTP con PostgreSQL real (Testcontainers,
 * ADR 0004) y un reloj de servidor controlable: {@link MutableClock} es
 * un bean primario de prueba, sin operación pública para mover el tiempo
 * ni esperas reales. Verifica los límites temporales exactos: un instante
 * antes del vencimiento redirige, al alcanzarlo ya no resuelve, las
 * visitas no renuevan y la asignación vencida permanece persistida (la
 * vigencia se evalúa por consulta al resolver; no hay tarea de limpieza).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {LinkShortenerApplication.class, ClockTestConfiguration.class},
        properties = {
                "shortener.public-base-url=http://192.168.50.10:8080",
                "shortener.reserved-routes=api, error"
        })
@Testcontainers
class VencimientoHttpTest {

    private static final Instant T0 = Instant.parse("2026-10-05T12:00:00Z");
    private static final Duration DURACION = Duration.ofMinutes(60);
    private static final String MENSAJE_404 = "Este enlace no existe o venció";

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

    @Test
    void antesDelVencimientoRedirigeYAlAlcanzarloDejaDeResolver() throws Exception {
        clock.set(T0);
        String alias = createAliasFor("https://ejemplo.com/limite");
        Instant expiresAt = T0.plus(DURACION);

        clock.set(expiresAt.minusNanos(1));
        HttpResponse<String> vigente = get("/" + alias);
        assertThat(vigente.statusCode()).isEqualTo(302);
        assertThat(vigente.headers().firstValue("Location"))
                .contains("https://ejemplo.com/limite");

        clock.set(expiresAt);
        HttpResponse<String> enElLimite = get("/" + alias);
        assertThat(enElLimite.statusCode()).isEqualTo(404);
        assertThat(enElLimite.body()).contains(MENSAJE_404);
        assertThat(enElLimite.headers().firstValue("Cache-Control")).contains("no-store");

        clock.set(expiresAt.plusNanos(1));
        HttpResponse<String> despues = get("/" + alias);
        assertThat(despues.statusCode()).isEqualTo(404);
        assertThat(despues.body()).contains(MENSAJE_404);
    }

    @Test
    void laAsignacionVencidaPermanecePersistidaSinTareaDeLimpieza() throws Exception {
        clock.set(T0);
        String alias = createAliasFor("https://ejemplo.com/historial");

        clock.set(T0.plus(Duration.ofHours(2)));
        assertThat(get("/" + alias).statusCode()).isEqualTo(404);

        // La vigencia se evalúa al resolver, no por borrado: el historial
        // conserva la asignación vencida y el alias sigue referenciándola.
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT a.id, a.destino, al.asignacion_actual_id"
                        + " FROM asignacion a JOIN alias al ON al.codigo = a.alias_codigo"
                        + " WHERE a.alias_codigo = ?", alias);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("destino")).isEqualTo("https://ejemplo.com/historial");
        assertThat(rows.get(0).get("asignacion_actual_id")).isEqualTo(rows.get(0).get("id"));
        assertThat(venceEn(alias)).isEqualTo(T0.plus(DURACION));
    }

    @Test
    void lasVisitasNoRenuevanElVencimiento() throws Exception {
        clock.set(T0);
        String alias = createAliasFor("https://ejemplo.com/sin-renovacion");

        clock.set(T0.plus(Duration.ofMinutes(10)));
        assertThat(get("/" + alias).statusCode()).isEqualTo(302);
        clock.set(T0.plus(Duration.ofMinutes(20)));
        assertThat(get("/" + alias).statusCode()).isEqualTo(302);
        clock.set(T0.plus(Duration.ofMinutes(59)));
        assertThat(get("/" + alias).statusCode()).isEqualTo(302);

        // Resolver no toca la asignación: conserva creación y vencimiento.
        assertThat(creadaEn(alias)).isEqualTo(T0);
        assertThat(venceEn(alias)).isEqualTo(T0.plus(DURACION));

        clock.set(T0.plus(Duration.ofMinutes(61)));
        assertThat(get("/" + alias).statusCode()).isEqualTo(404);
    }

    @Test
    void laValidezDependeDelInstanteQueEvaluaElServidor() throws Exception {
        clock.set(T0);
        String alias = createAliasFor("https://ejemplo.com/instante");
        Instant expiresAt = T0.plus(DURACION);

        // El servidor evalúa vigente un instante antes del límite.
        clock.set(expiresAt.minusNanos(1));
        HttpResponse<String> resueltaEnTiempo = get("/" + alias);

        // La respuesta puede llegar al navegador después del vencimiento:
        // su validez ya quedó decidida por el instante del servidor.
        clock.set(expiresAt.plus(Duration.ofMinutes(5)));
        assertThat(resueltaEnTiempo.statusCode()).isEqualTo(302);
        assertThat(resueltaEnTiempo.headers().firstValue("Location"))
                .contains("https://ejemplo.com/instante");

        // La próxima resolución evalúa otro instante: ya no redirige.
        assertThat(get("/" + alias).statusCode()).isEqualTo(404);
    }

    private Instant creadaEn(String alias) {
        return db.queryForObject(
                        "SELECT creada_en FROM asignacion WHERE alias_codigo = ?",
                        OffsetDateTime.class, alias)
                .toInstant();
    }

    private Instant venceEn(String alias) {
        return db.queryForObject(
                        "SELECT vence_en FROM asignacion WHERE alias_codigo = ?",
                        OffsetDateTime.class, alias)
                .toInstant();
    }

    private String createAliasFor(String destination) throws Exception {
        HttpResponse<String> response = postJson("{\"destination\":\"" + destination + "\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        assertThat(Instant.parse(body.get("expiresAt").asText()))
                .isEqualTo(clock.instant().plus(DURACION));
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
