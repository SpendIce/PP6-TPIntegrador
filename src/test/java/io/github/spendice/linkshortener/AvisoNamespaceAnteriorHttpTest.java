package io.github.spendice.linkshortener;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aviso en el namespace anterior durante la gracia (issue #25) sobre
 * PostgreSQL real (Testcontainers, ADR 0004), el proveedor OIDC simulado
 * (ADR 0007) y el {@link MutableClock}: con el nombre anterior en gracia
 * y el enlace vigente, la dirección anterior responde 200 con la página
 * de aviso —nueva dirección completa, hora límite (la primera entre el
 * fin de la gracia y el vencimiento del enlace), demora, cuenta
 * regresiva, copiar e «Ir ahora»— en vez de redirigir; los cambios
 * encadenados llevan al namespace actual; al terminar la gracia o con el
 * enlace vencido o inexistente responde el 404 acordado, y si otro
 * usuario tomó el nombre conduce a su destino con 302. La infraestructura
 * compartida está en {@link HttpApiFixture}.
 */
@Import(ClockTestConfiguration.class)
class AvisoNamespaceAnteriorHttpTest extends HttpApiFixture {

    private static final Instant T0 = Instant.parse("2026-10-05T12:00:00Z");
    private static final String MENSAJE_404 = "Este enlace no existe o venció";

    @Autowired
    MutableClock clock;

    @Test
    void elNamespaceAnteriorEnGraciaSirveElAvisoEnVezDeRedirigir() throws Exception {
        clock.set(T0);
        HttpClient juan = usuarioConNamespace("aviso-juan", "aviso-juan@ejemplo.com", "juan");
        postEnlacePersonal(juan, "https://ejemplo.com/doc", "mi-doc");

        // A las 12:10 cambia a juani: la gracia de «juan» termina 13:10 y
        // el enlace vence 13:00; el límite del aviso es el vencimiento.
        clock.set(T0.plusSeconds(600));
        assertThat(postNamespace(juan, "juani").statusCode()).isEqualTo(200);

        HttpResponse<String> aviso = get("/juan/mi-doc");
        assertThat(aviso.statusCode()).isEqualTo(200);
        assertThat(aviso.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(aviso.headers().firstValue("Content-Type")).contains("text/html");

        String nueva = PUBLIC_BASE + "/juani/mi-doc";
        String html = aviso.body();
        assertThat(html).contains(nueva);
        assertThat(html).contains("datetime=\"2026-10-05T13:00:00Z\"");
        // La demora por defecto (PT5S) llega al HTML en los dos formatos
        // que la página consume: meta refresh y cuenta regresiva.
        assertThat(html).contains("content=\"5;url=" + nueva + "\"");
        assertThat(html).contains("<span id=\"cuenta\">5</span>");
        assertThat(html).contains("Ir ahora");

        // La cuenta regresiva y el copiado viven en el script estático
        // que la página carga: tiene que servirse.
        assertThat(get("/aviso.js").statusCode()).isEqualTo(200);

        // El nombre actual sigue redirigiendo directamente.
        HttpResponse<String> actual = get("/juani/mi-doc");
        assertThat(actual.statusCode()).isEqualTo(302);
        assertThat(actual.headers().firstValue("Location"))
                .contains("https://ejemplo.com/doc");
    }

    @Test
    void unEnlaceCreadoDespuesDelCambioRespondeConElAvisoYElLimiteEsElFinDeLaGracia()
            throws Exception {
        clock.set(T0);
        HttpClient juan = usuarioConNamespace("aviso-tarde", "aviso-tarde@ejemplo.com", "juan");
        assertThat(postNamespace(juan, "juani").statusCode()).isEqualTo(200);

        // El enlace nace como juani/tarde a las 12:20 (vence 13:20): la
        // gracia de «juan» termina antes (13:00) y es la hora límite.
        clock.set(T0.plusSeconds(1200));
        assertThat(postEnlacePersonal(juan, "https://ejemplo.com/tarde", "tarde").statusCode())
                .isEqualTo(201);

        HttpResponse<String> aviso = get("/juan/tarde");
        assertThat(aviso.statusCode()).isEqualTo(200);
        assertThat(aviso.body())
                .contains(PUBLIC_BASE + "/juani/tarde")
                .contains("datetime=\"2026-10-05T13:00:00Z\"");
    }

    @Test
    void conCambiosEncadenadosTodosLosAnterioresLlevanAlNamespaceActual() throws Exception {
        clock.set(T0);
        HttpClient juan = usuarioConNamespace("aviso-cadena", "aviso-cadena@ejemplo.com", "juan");
        postEnlacePersonal(juan, "https://ejemplo.com/doc", "mi-doc");
        assertThat(postNamespace(juan, "juani").statusCode()).isEqualTo(200);
        clock.set(T0.plusSeconds(1800));
        assertThat(postNamespace(juan, "juanito").statusCode()).isEqualTo(200);

        // juan (gracia hasta 13:00) y juani (hasta 13:30) anuncian el
        // mismo destino con el namespace actual; el enlace vence 13:00 y
        // es el límite de ambos avisos.
        String nueva = PUBLIC_BASE + "/juanito/mi-doc";
        HttpResponse<String> desdeJuan = get("/juan/mi-doc");
        assertThat(desdeJuan.statusCode()).isEqualTo(200);
        assertThat(desdeJuan.body())
                .contains(nueva)
                .contains("datetime=\"2026-10-05T13:00:00Z\"");

        HttpResponse<String> desdeJuani = get("/juani/mi-doc");
        assertThat(desdeJuani.statusCode()).isEqualTo(200);
        assertThat(desdeJuani.body())
                .contains(nueva)
                .contains("datetime=\"2026-10-05T13:00:00Z\"");
    }

    @Test
    void alTerminarLaGraciaOConElEnlaceVencidoOInexistenteResponde404() throws Exception {
        clock.set(T0);
        HttpClient juan = usuarioConNamespace("aviso-404", "aviso-404@ejemplo.com", "juan");
        postEnlacePersonal(juan, "https://ejemplo.com/doc", "mi-doc");
        clock.set(T0.plusSeconds(600));
        assertThat(postNamespace(juan, "juani").statusCode()).isEqualTo(200);

        // Alias inexistente bajo el nombre anterior en gracia: 404 ya.
        assertNoEncontrado(get("/juan/nada"));

        // El enlace venció (13:00) aunque la gracia sigue hasta 13:10.
        clock.set(T0.plusSeconds(3660));
        assertNoEncontrado(get("/juan/mi-doc"));

        // La gracia también terminó (13:10): la dirección sigue en 404.
        clock.set(T0.plusSeconds(4300));
        assertNoEncontrado(get("/juan/mi-doc"));
    }

    @Test
    void terminadaLaGraciaElNombreTomadoPorOtroConduceASuDestino() throws Exception {
        clock.set(T0);
        HttpClient juan = usuarioConNamespace("aviso-duenio-j", "aviso-duenio-j@ejemplo.com", "juan");
        postEnlacePersonal(juan, "https://ejemplo.com/de-juan", "mi-doc");
        assertThat(postNamespace(juan, "juani").statusCode()).isEqualTo(200);
        HttpClient pedro = sesionDe("aviso-duenio-p", "aviso-duenio-p@ejemplo.com");

        // La gracia terminó y Pedro toma «juan»: el mismo alias
        // personalizado de Pedro es una asignación distinta que conduce
        // a su destino, como cualquier namespace actual.
        clock.set(T0.plusSeconds(3700));
        assertThat(postNamespace(pedro, "juan").statusCode()).isEqualTo(200);
        assertThat(postEnlacePersonal(pedro, "https://ejemplo.com/de-pedro", "mi-doc").statusCode())
                .isEqualTo(201);

        HttpResponse<String> resolucion = get("/juan/mi-doc");
        assertThat(resolucion.statusCode()).isEqualTo(302);
        assertThat(resolucion.headers().firstValue("Location"))
                .contains("https://ejemplo.com/de-pedro");
    }

    @Test
    void unNombreRecuperadoPorSuUsuarioVuelveARedirigirDirectamente() throws Exception {
        clock.set(T0);
        HttpClient juan = usuarioConNamespace("aviso-recupera", "aviso-recupera@ejemplo.com", "juan");
        postEnlacePersonal(juan, "https://ejemplo.com/doc", "mi-doc");
        assertThat(postNamespace(juan, "juani").statusCode()).isEqualTo(200);
        assertThat(get("/juan/mi-doc").statusCode()).isEqualTo(200);

        // La resolución busca primero entre los namespaces actuales:
        // al recuperar «juan» dentro de la gracia vuelve a ser actual y
        // la dirección conduce al destino con 302, sin aviso.
        assertThat(postNamespace(juan, "juan").statusCode()).isEqualTo(200);
        HttpResponse<String> resolucion = get("/juan/mi-doc");
        assertThat(resolucion.statusCode()).isEqualTo(302);
        assertThat(resolucion.headers().firstValue("Location"))
                .contains("https://ejemplo.com/doc");
    }

    @Test
    void sinJavaScriptSeLlegaAlDestinoConElEnlaceIrAhora() throws Exception {
        clock.set(T0);
        HttpClient juan = usuarioConNamespace("aviso-ir", "aviso-ir@ejemplo.com", "juan");
        postEnlacePersonal(juan, "https://ejemplo.com/doc", "mi-doc");
        assertThat(postNamespace(juan, "juani").statusCode()).isEqualTo(200);

        // «Ir ahora» es un enlace real a la nueva dirección: sin
        // JavaScript se la puede seguir y conduce al destino con 302.
        String html = get("/juan/mi-doc").body();
        assertThat(html).contains("id=\"ir-ahora\" href=\"" + PUBLIC_BASE + "/juani/mi-doc\"");
        HttpResponse<String> destino = get("/juani/mi-doc");
        assertThat(destino.statusCode()).isEqualTo(302);
        assertThat(destino.headers().firstValue("Location"))
                .contains("https://ejemplo.com/doc");
    }

    // ---- Helpers ----

    private void assertNoEncontrado(HttpResponse<String> respuesta) {
        assertThat(respuesta.statusCode()).isEqualTo(404);
        assertThat(respuesta.body()).contains(MENSAJE_404);
        assertThat(respuesta.headers().firstValue("Cache-Control")).contains("no-store");
    }

}
