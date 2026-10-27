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

@Import(ClockTestConfiguration.class)
class CambioNamespaceHttpTest extends HttpApiFixture {

    private static final Instant T0 = Instant.parse("2026-10-05T12:00:00Z");

    @Autowired
    MutableClock clock;

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

    @Test
    void dosUsuariosQuePidenALaVezUnNamespaceLibreNoLoComparten() throws Exception {
        HttpClient uno = sesionDe("google-sub-gracia-race-uno", "race-uno@ejemplo.com");
        HttpClient dos = sesionDe("google-sub-gracia-race-dos", "race-dos@ejemplo.com");
        var barrera = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var primera = pool.submit(() -> {
                barrera.await(10, TimeUnit.SECONDS);
                return cambiar(uno, "libre").statusCode();
            });
            var segunda = pool.submit(() -> {
                barrera.await(10, TimeUnit.SECONDS);
                return cambiar(dos, "libre").statusCode();
            });
            assertThat(List.of(primera.get(20, TimeUnit.SECONDS),
                    segunda.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
            assertThat(db.queryForObject(
                    "SELECT COUNT(*) FROM nombre_namespace WHERE namespace = 'libre'", Long.class))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
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
