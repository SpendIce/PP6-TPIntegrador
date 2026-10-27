package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
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

    @Test
    void cambiaNamespaceReservaCadaNombreYPermiteRecuperarlo() throws Exception {
        clock.set(T0);
        HttpClient juan = usuario("google-sub-gracia-juan", "juan@ejemplo.com", "juan");
        HttpClient pedro = sesionDe("google-sub-gracia-pedro", "pedro@ejemplo.com");

        assertThat(cambiar(juan, "juani").statusCode()).isEqualTo(200);
        assertError(cambiar(pedro, "juan"), 409, "NAMESPACE_TAKEN");
        assertThat(cambiar(juan, "juanito").statusCode()).isEqualTo(200);
        JsonNode lista = json.readTree(get(juan, "/api/namespace/anteriores").body());
        assertThat(lista.get("namespaces").size()).isEqualTo(2);
        assertThat(lista.get("namespaces").get(0).get("graceEndsAt").asText())
                .isEqualTo("2026-10-05T13:00:00Z");

        assertThat(cambiar(juan, "juan").statusCode()).isEqualTo(200);
        assertThat(db.queryForList("SELECT namespace FROM namespace_anterior ORDER BY namespace",
                String.class)).containsExactly("juani", "juanito");
        assertThat(json.readTree(get(juan, "/api/namespace/anteriores").body())
                .get("namespaces").size()).isEqualTo(2);
        assertThat(db.queryForList(
                "SELECT namespace FROM nombre_namespace WHERE grace_ends_at IS NULL",
                String.class)).containsExactly("juan");
    }

    @Test
    void nombreQuedaLibreAlFinDeLaGraciaYNoCambiaVencimientosPersonales() throws Exception {
        clock.set(T0);
        HttpClient juan = usuario("google-sub-gracia-juan2", "juan2@ejemplo.com", "juan");
        HttpClient pedro = sesionDe("google-sub-gracia-pedro2", "pedro2@ejemplo.com");
        HttpResponse<String> creado = postJson(juan, "/api/links",
                "{\"destination\":\"https://ejemplo.com\",\"aliasPersonalizado\":\"doc\"}");
        assertThat(creado.statusCode()).isEqualTo(201);
        java.sql.Timestamp vencimiento = db.queryForObject(
                "SELECT vence_en FROM asignacion_personal WHERE alias_personalizado = 'doc'",
                java.sql.Timestamp.class);

        assertThat(cambiar(juan, "juani").statusCode()).isEqualTo(200);
        HttpResponse<String> enlaceBajoNuevoNombre = get(client, "/juani/doc");
        assertThat(enlaceBajoNuevoNombre.statusCode()).isEqualTo(302);
        assertThat(enlaceBajoNuevoNombre.headers().firstValue("Location").orElseThrow())
                .isEqualTo("https://ejemplo.com");
        clock.set(T0.plusSeconds(3600));
        assertThat(cambiar(pedro, "juan").statusCode()).isEqualTo(200);
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
            var primera = pool.submit(() -> cambiar(uno, "libre"));
            var segunda = pool.submit(() -> cambiar(dos, "libre"));
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
        HttpClient juan = usuario("google-sub-gracia-carrera-juan",
                "carrera-juan@ejemplo.com", "juan-inicial");
        HttpClient pedro = usuario("google-sub-gracia-carrera-pedro",
                "carrera-pedro@ejemplo.com", "pedro-inicial");
        assertThat(cambiar(juan, "medio").statusCode()).isEqualTo(200);

        var barrera = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var suelta = pool.submit(() -> {
                barrera.await(10, TimeUnit.SECONDS);
                return cambiar(juan, "juan-final");
            });
            var intenta = pool.submit(() -> {
                barrera.await(10, TimeUnit.SECONDS);
                return cambiar(pedro, "medio");
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

    @Test
    void cambioAplicaFormatoYNombresReservados() throws Exception {
        HttpClient juan = usuario("google-sub-gracia-validacion", "valida@ejemplo.com", "juan");
        assertError(cambiar(juan, "-mal"), 400, "NAMESPACE_INVALID");
        assertError(cambiar(juan, "api"), 400, "NAMESPACE_RESERVED");
        assertThat(db.queryForObject(
                "SELECT namespace FROM usuario WHERE google_sub = 'google-sub-gracia-validacion'",
                String.class)).isEqualTo("juan");
    }

    private HttpClient usuario(String sub, String email, String namespace) throws Exception {
        HttpClient cliente = sesionDe(sub, email);
        assertThat(cambiar(cliente, namespace).statusCode()).isEqualTo(200);
        return cliente;
    }

    private HttpClient sesionDe(String sub, String email) throws Exception {
        HttpClient dispositivo = nuevoClienteDispositivo();
        GoogleSimulado.login(dispositivo, baseUrl(), sub, email);
        return dispositivo;
    }

    private HttpResponse<String> cambiar(HttpClient cliente, String namespace) throws Exception {
        return postJson(cliente, "/api/namespace", "{\"namespace\":\"" + namespace + "\"}");
    }
}
