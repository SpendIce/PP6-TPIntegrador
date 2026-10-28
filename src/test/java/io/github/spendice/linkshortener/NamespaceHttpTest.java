package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Elección del namespace en el primer login (issue #20) sobre PostgreSQL
 * real (Testcontainers, ADR 0004) y el proveedor OIDC simulado (ADR
 * 0007): sugerencia derivada del email, formato y normalización a
 * minúsculas, unicidad sin distinguir mayúsculas, nombres reservados,
 * un solo namespace por usuario y carrera entre dos usuarios por el
 * mismo nombre. La infraestructura compartida está en
 * {@link HttpApiFixture}.
 */
class NamespaceHttpTest extends HttpApiFixture {

    private static final String SUB_JUAN = "google-sub-namespace-juan";
    private static final String EMAIL_JUAN = "juan.perez+tag@ejemplo.com";
    private static final String SUB_PEDRO = "google-sub-namespace-pedro";
    private static final String EMAIL_PEDRO = "pedro@ejemplo.com";

    @Test
    void laSesionSugiereElNamespaceDesdeLaParteLocalDelEmail() throws Exception {
        HttpClient juan = sesionDe(SUB_JUAN, EMAIL_JUAN);

        JsonNode usuario = json.readTree(get(juan, "/api/sesion").body()).get("usuario");

        assertThat(usuario.get("namespace").asText()).isEmpty();
        assertThat(usuario.get("sugerencia").asText()).isEqualTo("juan-perez-tag");

        JsonNode sugerencia = json.readTree(get(juan, "/api/namespace/sugerencia").body());
        assertThat(sugerencia.get("sugerencia").asText()).isEqualTo("juan-perez-tag");
    }

    @Test
    void elegirUnNamespaceValidoLoGuardaEnMinusculasYLaSesionLoDevuelve() throws Exception {
        HttpClient juan = sesionDe(SUB_JUAN, EMAIL_JUAN);

        HttpResponse<String> respuesta = elegir(juan, "Juan-Perez");

        assertThat(respuesta.statusCode()).isEqualTo(200);
        assertThat(json.readTree(respuesta.body()).get("usuario").get("namespace").asText())
                .isEqualTo("juan-perez");
        assertThat(db.queryForObject(
                "SELECT namespace FROM usuario WHERE google_sub = ?", String.class, SUB_JUAN))
                .isEqualTo("juan-perez");
        JsonNode sesion = json.readTree(get(juan, "/api/sesion").body()).get("usuario");
        assertThat(sesion.get("namespace").asText()).isEqualTo("juan-perez");
    }

    @Test
    void rechazaLosFormatosInvalidosConSuPropioCodigo() throws Exception {
        HttpClient juan = sesionDe(SUB_JUAN, EMAIL_JUAN);

        List<String> invalidos = List.of(
                "ab",
                "a".repeat(31),
                "-juan",
                "juan-",
                "juan_perez",
                "juan perez",
                "juán",
                "");

        for (String invalido : invalidos) {
            assertError(elegir(juan, invalido), 400, "NAMESPACE_INVALID");
        }
        // Ningún rechazo guardó nada: el usuario sigue sin namespace.
        assertThat(db.queryForObject(
                "SELECT namespace FROM usuario WHERE google_sub = ?", String.class, SUB_JUAN))
                .isNull();
    }

    @Test
    void rechazaLosNombresReservadosSinDistinguirMayusculas() throws Exception {
        HttpClient juan = sesionDe(SUB_JUAN, EMAIL_JUAN);

        for (String reservado : List.of("api", "LOGIN", "logout", "vincular", "oauth2", "error")) {
            assertError(elegir(juan, reservado), 400, "NAMESPACE_RESERVED");
        }
    }

    @Test
    void rechazaUnNamespaceTomadoSinDistinguirMayusculas() throws Exception {
        HttpClient juan = sesionDe(SUB_JUAN, EMAIL_JUAN);
        HttpClient pedro = sesionDe(SUB_PEDRO, EMAIL_PEDRO);
        assertThat(elegir(juan, "juan").statusCode()).isEqualTo(200);

        assertError(elegir(pedro, "JUAN"), 409, "NAMESPACE_TAKEN");

        // El rechazo no le quita a Pedro la posibilidad de escribir otro.
        HttpResponse<String> alternativa = elegir(pedro, "pedro");
        assertThat(alternativa.statusCode()).isEqualTo(200);
        assertThat(json.readTree(alternativa.body()).get("usuario").get("namespace").asText())
                .isEqualTo("pedro");
    }

    @Test
    void usuarioPuedeCambiarNamespaceYRepetirElActualEsIdempotente() throws Exception {
        HttpClient juan = sesionDe(SUB_JUAN, EMAIL_JUAN);
        assertThat(elegir(juan, "juan").statusCode()).isEqualTo(200);

        assertError(elegir(juan, "ab"), 400, "NAMESPACE_INVALID");
        assertError(elegir(juan, "api"), 400, "NAMESPACE_RESERVED");

        // Repetir el mismo nombre es idempotente y no se interpreta como cambio.
        assertThat(elegir(juan, "JUAN").statusCode()).isEqualTo(200);
        assertThat(db.queryForObject(
                "SELECT namespace FROM usuario WHERE google_sub = ?", String.class, SUB_JUAN))
                .isEqualTo("juan");
        assertThat(elegir(juan, "juanito").statusCode()).isEqualTo(200);
        assertThat(db.queryForObject(
                "SELECT namespace FROM usuario WHERE google_sub = ?", String.class, SUB_JUAN))
                .isEqualTo("juanito");
    }

    @Test
    void aceptaLosLimitesDeTresYTreintaCaracteres() throws Exception {
        HttpClient juan = sesionDe(SUB_JUAN, EMAIL_JUAN);
        HttpClient pedro = sesionDe(SUB_PEDRO, EMAIL_PEDRO);

        assertThat(elegir(juan, "abc").statusCode()).isEqualTo(200);
        String treinta = "a".repeat(29) + "b";
        assertThat(treinta).hasSize(30);
        assertThat(elegir(pedro, treinta).statusCode()).isEqualTo(200);
        assertThat(db.queryForObject(
                "SELECT namespace FROM usuario WHERE google_sub = ?", String.class, SUB_PEDRO))
                .isEqualTo(treinta);
    }

    @Test
    void laSugerenciaSeAdaptaAlFormatoDelNamespace() throws Exception {
        // Parte local demasiado corta: cae al valor por defecto.
        assertThat(sugerenciaDe("ab@ejemplo.com")).isEqualTo("usuario");
        // Truncado a 30 sin dejar un guion final.
        assertThat(sugerenciaDe("a".repeat(40) + "@ejemplo.com"))
                .hasSize(30)
                .doesNotEndWith("-");
        // Separadores repetidos y finales se colapsan.
        assertThat(sugerenciaDe("juan...perez--@ejemplo.com")).isEqualTo("juan-perez");
    }

    @Test
    void sinSesionResponde401ConElFormatoDeError() throws Exception {
        HttpClient dispositivo = nuevoClienteDispositivo();

        HttpResponse<String> alta = elegir(dispositivo, "juan");
        assertError(alta, 401, "NOT_AUTHENTICATED");

        HttpResponse<String> sugerencia = get(dispositivo, "/api/namespace/sugerencia");
        assertError(sugerencia, 401, "NOT_AUTHENTICATED");
    }

    @Test
    void dosUsuariosQuePidenALaVezElMismoNamespaceNoTerminanAmbosConEl() throws Exception {
        HttpClient juan = sesionDe(SUB_JUAN, EMAIL_JUAN);
        HttpClient pedro = sesionDe(SUB_PEDRO, EMAIL_PEDRO);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier salida = new CyclicBarrier(2);
        try {
            Callable<HttpResponse<String>> juanPide = () -> {
                salida.await(10, TimeUnit.SECONDS);
                return elegir(juan, "mismo-nombre");
            };
            Callable<HttpResponse<String>> pedroPide = () -> {
                salida.await(10, TimeUnit.SECONDS);
                return elegir(pedro, "mismo-nombre");
            };
            Future<HttpResponse<String>> primero = pool.submit(juanPide);
            Future<HttpResponse<String>> segundo = pool.submit(pedroPide);

            List<HttpResponse<String>> respuestas = List.of(
                    primero.get(30, TimeUnit.SECONDS),
                    segundo.get(30, TimeUnit.SECONDS));

            assertThat(respuestas).extracting(HttpResponse::statusCode)
                    .containsExactlyInAnyOrder(200, 409);
            HttpResponse<String> rechazo = respuestas.stream()
                    .filter(r -> r.statusCode() == 409).findFirst().orElseThrow();
            assertError(rechazo, 409, "NAMESPACE_TAKEN");
            assertThat(db.queryForObject(
                    "SELECT COUNT(*) FROM usuario WHERE namespace = ?",
                    Long.class, "mismo-nombre")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Inicia sesión con Google simulado y deja la cookie en el cliente. */
    private HttpClient sesionDe(String sub, String email) throws Exception {
        HttpClient dispositivo = nuevoClienteDispositivo();
        GoogleSimulado.login(dispositivo, baseUrl(), sub, email);
        return dispositivo;
    }

    /** Sugerencia que la web recibe para el email indicado. */
    private String sugerenciaDe(String email) throws Exception {
        HttpClient dispositivo = sesionDe("google-sub-sugerencia-" + email, email);
        return json.readTree(get(dispositivo, "/api/namespace/sugerencia").body())
                .get("sugerencia").asText();
    }

    private HttpResponse<String> elegir(HttpClient dispositivo, String namespace)
            throws Exception {
        return postJson(dispositivo, "/api/namespace",
                "{\"namespace\":\"" + namespace + "\"}");
    }
}
