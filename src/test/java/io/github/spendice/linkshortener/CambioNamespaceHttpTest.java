package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "cuenta.namespace-grace-period=PT1H")
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
        String vencimiento = db.queryForObject(
                "SELECT vence_en::text FROM asignacion_personal WHERE alias_personalizado = 'doc'",
                String.class);

        assertThat(cambiar(juan, "juani").statusCode()).isEqualTo(200);
        clock.set(T0.plusSeconds(3600));
        assertThat(cambiar(pedro, "juan").statusCode()).isEqualTo(200);
        assertThat(db.queryForObject(
                "SELECT vence_en::text FROM asignacion_personal WHERE alias_personalizado = 'doc'",
                String.class)).isEqualTo(vencimiento);
        assertThat(get(client, "/juani/doc").statusCode()).isEqualTo(302);
    }

    private HttpClient usuario(String sub, String email, String namespace) throws Exception {
        HttpClient cliente = sesionDe(sub, email);
        assertThat(cambiar(cliente, namespace).statusCode()).isEqualTo(200);
        return cliente;
    }

    private HttpResponse<String> cambiar(HttpClient cliente, String namespace) throws Exception {
        return postJson(cliente, "/api/namespace", "{\"namespace\":\"" + namespace + "\"}");
    }
}
