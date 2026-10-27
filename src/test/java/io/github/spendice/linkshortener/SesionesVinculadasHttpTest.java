package io.github.spendice.linkshortener;

import io.github.spendice.linkshortener.service.CuentaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** Cierre masivo de sesiones vinculadas con PostgreSQL y clientes HTTP aislados. */
class SesionesVinculadasHttpTest extends HttpApiFixture {

    private static final String SUB_JUAN = "google-sub-sesiones-juan";
    private static final String EMAIL_JUAN = "sesiones-juan@ejemplo.com";
    private static final String SUB_PEDRO = "google-sub-sesiones-pedro";
    private static final String EMAIL_PEDRO = "sesiones-pedro@ejemplo.com";

    @Autowired
    JdbcIndexedSessionRepository sesiones;

    @Test
    void sesionPrincipalCierraSoloLasVinculadasDeSuUsuario() throws Exception {
        HttpClient principal = nuevoClienteDispositivo();
        GoogleSimulado.login(principal, baseUrl(), SUB_JUAN, EMAIL_JUAN);
        HttpClient vinculada = sesionVinculadaDe(SUB_JUAN);
        HttpClient otroUsuario = nuevoClienteDispositivo();
        GoogleSimulado.login(otroUsuario, baseUrl(), SUB_PEDRO, EMAIL_PEDRO);

        HttpResponse<String> cierreVinculado = cerrarSesionesVinculadas(vinculada);
        assertError(cierreVinculado, 403, "SESSION_NOT_PRIMARY");
        assertThat(get(vinculada, "/api/sesion").body()).contains("vinculada");

        HttpResponse<String> cierre = cerrarSesionesVinculadas(principal);

        assertThat(cierre.statusCode()).isEqualTo(204);
        assertThat(cierre.body()).isEmpty();
        assertThat(get(principal, "/api/sesion").body()).contains(EMAIL_JUAN);
        assertThat(get(vinculada, "/api/sesion").body()).contains("\"usuario\":null");
        assertThat(get(otroUsuario, "/api/sesion").body()).contains(EMAIL_PEDRO);
    }

    @Test
    void cerrarLaSesionPrincipalNoInvalidaLaVinculada() throws Exception {
        HttpClient principal = nuevoClienteDispositivo();
        GoogleSimulado.login(principal, baseUrl(), SUB_JUAN, EMAIL_JUAN);
        HttpClient vinculada = sesionVinculadaDe(SUB_JUAN);

        HttpResponse<String> cierre = postJson(principal, "/api/logout", "{}");

        assertThat(cierre.statusCode()).isEqualTo(204);
        assertThat(get(principal, "/api/sesion").body()).contains("\"usuario\":null");
        assertThat(get(vinculada, "/api/sesion").body()).contains(EMAIL_JUAN);
    }

    private HttpClient sesionVinculadaDe(String sub) throws Exception {
        // El flujo de QR corresponde a su Change; para este contrato se
        // parte de una sesión HTTP real y se prepara su tipo vinculado.
        HttpClient dispositivo = nuevoClienteDispositivo();
        String email = sub.equals(SUB_JUAN) ? EMAIL_JUAN : EMAIL_PEDRO;
        GoogleSimulado.login(dispositivo, baseUrl(), sub, email);
        Session sesion = (Session) ((FindByIndexNameSessionRepository) sesiones)
                .findById(cookieDeSesion(dispositivo));
        sesion.setAttribute(CuentaService.ATRIBUTO_SESION_TIPO, CuentaService.TIPO_VINCULADA);
        guardarSesion(sesion);
        return dispositivo;
    }

    private String cookieDeSesion(HttpClient dispositivo) {
        CookieManager almacen = (CookieManager) dispositivo.cookieHandler().orElseThrow();
        return almacen.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("SESSION"))
                .findFirst()
                .map(HttpCookie::getValue)
                .map(valor -> new String(
                        Base64.getUrlDecoder().decode(valor), StandardCharsets.UTF_8))
                .orElseThrow();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void guardarSesion(Session sesion) {
        ((FindByIndexNameSessionRepository) sesiones).save(sesion);
    }

    private HttpResponse<String> cerrarSesionesVinculadas(HttpClient dispositivo)
            throws Exception {
        String csrf = Csrf.obtener(dispositivo, baseUrl());
        HttpRequest request = Csrf.aplicar(dispositivo,
                        HttpRequest.newBuilder(URI.create(
                                baseUrl() + "/api/sesiones-vinculadas/cerrar")), csrf)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return dispositivo.send(request, BodyHandlers.ofString());
    }
}
