package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reinicio del servicio contra el MISMO PostgreSQL (Testcontainers,
 * ADR 0004): dos contextos Spring sucesivos — el primero se cierra antes
 * de levantar el segundo — verifican que reiniciar no renueva ni pierde
 * asignaciones, que destino, identidad, creación y vencimiento se
 * conservan, que el tiempo transcurrido con el servicio apagado cuenta
 * para la vigencia y que tras reiniciar se reciclan los alias vencidos
 * conservando el avance del generador.
 */
@Testcontainers
class ReinicioServicioHttpTest {

    private static final Instant T0 = Instant.parse("2026-10-05T12:00:00Z");
    private static final Duration DURACION = Duration.ofMinutes(60);
    private static final String MENSAJE_404 = "Este enlace no existe o venció";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void espacioDeCodigosVacio() {
        // El esquema solo existe después del primer contexto: la primera
        // limpieza puede fallar sin consecuencias porque la base ya está
        // vacía.
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE alias, asignacion RESTART IDENTITY");
            statement.execute("UPDATE generador_alias SET proximo_indice = 0");
        } catch (SQLException ignored) {
            // Antes de Flyway no hay tablas que limpiar.
        }
    }

    @Test
    void reiniciarConservaElEstadoYElTiempoApagadoCuenta() throws Exception {
        Instant creadaVigente = T0.plus(Duration.ofMinutes(30));
        Instant instanteReinicio = T0.plus(Duration.ofMinutes(61));

        String aliasVencido;
        String aliasVigente;
        ConfigurableApplicationContext primera = startApp();
        try {
            MutableClock reloj = primera.getBean(MutableClock.class);
            reloj.set(T0);
            aliasVencido = createAlias(port(primera), "https://ejemplo.com/que-vence");
            reloj.set(creadaVigente);
            aliasVigente = createAlias(port(primera), "https://ejemplo.com/que-sigue");
        } finally {
            primera.close();
        }

        // El servicio permanece apagado 31 minutos: ese tiempo también
        // cuenta para la vigencia, no se detiene con el proceso.
        ConfigurableApplicationContext reiniciada = startApp();
        try {
            reiniciada.getBean(MutableClock.class).set(instanteReinicio);
            int puerto = port(reiniciada);

            HttpResponse<String> vencida = get(puerto, "/" + aliasVencido);
            assertThat(vencida.statusCode()).isEqualTo(404);
            assertThat(vencida.body()).contains(MENSAJE_404);

            HttpResponse<String> vigente = get(puerto, "/" + aliasVigente);
            assertThat(vigente.statusCode()).isEqualTo(302);
            assertThat(vigente.headers().firstValue("Location"))
                    .contains("https://ejemplo.com/que-sigue");

            // El reinicio no renueva ni altera lo persistido.
            JdbcTemplate db = reiniciada.getBean(JdbcTemplate.class);
            assertAsignacionPersistida(db, aliasVencido,
                    "https://ejemplo.com/que-vence", T0, T0.plus(DURACION));
            assertAsignacionPersistida(db, aliasVigente,
                    "https://ejemplo.com/que-sigue", creadaVigente, creadaVigente.plus(DURACION));
        } finally {
            reiniciada.close();
        }
    }

    @Test
    void trasReiniciarSeReciclanLosVencidosYElGeneradorConservaSuAvance() throws Exception {
        String aliasVencido;
        ConfigurableApplicationContext primera = startApp();
        try {
            primera.getBean(MutableClock.class).set(T0);
            aliasVencido = createAlias(port(primera), "https://ejemplo.com/que-vence");
        } finally {
            primera.close();
        }

        ConfigurableApplicationContext reiniciada = startApp();
        try {
            // Tras el reinicio la asignación ya venció: la creación la
            // recicla en lugar de emitir un código nuevo.
            reiniciada.getBean(MutableClock.class).set(T0.plus(DURACION).plusNanos(1));
            int puerto = port(reiniciada);

            assertThat(createAlias(puerto, "https://ejemplo.com/nuevo-dueno"))
                    .isEqualTo(aliasVencido);

            // El historial conserva ambas asignaciones y la resolución
            // del alias conduce al destino de la vigente.
            JdbcTemplate db = reiniciada.getBean(JdbcTemplate.class);
            assertThat(db.queryForObject(
                    "SELECT COUNT(*) FROM asignacion WHERE alias_codigo = ?",
                    Long.class, aliasVencido)).isEqualTo(2);
            HttpResponse<String> redirect = get(puerto, "/" + aliasVencido);
            assertThat(redirect.statusCode()).isEqualTo(302);
            assertThat(redirect.headers().firstValue("Location"))
                    .contains("https://ejemplo.com/nuevo-dueno");

            // Sin más vencidos, el avance persistido emite el siguiente
            // código nunca usado, sin reconstruir el espacio.
            assertThat(createAlias(puerto, "https://ejemplo.com/otro")).isEqualTo("2");
        } finally {
            reiniciada.close();
        }
    }

    private void assertAsignacionPersistida(JdbcTemplate db, String alias,
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

    private ConfigurableApplicationContext startApp() {
        // Las propiedades viajan como argumentos: tienen precedencia sobre
        // los defaults de application.yml (DB_HOST, DB_USER, ...).
        return new SpringApplicationBuilder(LinkShortenerApplication.class)
                .sources(ClockTestConfiguration.class)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--shortener.public-base-url=http://192.168.50.10:8080");
    }

    private int port(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    private String createAlias(int port, String destination) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl(port) + "/api/links"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"destination\":\"" + destination + "\"}"))
                .build();
        HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        return body.get("alias").asText();
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl(port) + path)).GET().build();
        return client.send(request, BodyHandlers.ofString());
    }

    private String baseUrl(int port) {
        return "http://localhost:" + port;
    }
}
