package io.github.spendice.linkshortener;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La inactividad máxima de la sesión principal es configurable (issue #19):
 * con {@code PT12H} en vez del default, la sesión persistida tiene que llevar
 * esa inactividad. Contexto propio por la propiedad; PostgreSQL y proveedor
 * OIDC simulados son los del fixture.
 */
@TestPropertySource(properties = "cuenta.main-session-idle-timeout=PT12H")
class SesionInactividadConfiguradaHttpTest extends HttpApiFixture {

    @Test
    void aplicaLaInactividadConfiguradaEnLaSesionPersistida() throws Exception {
        HttpClient dispositivo = nuevoClienteDispositivo();

        GoogleSimulado.login(dispositivo, baseUrl(),
                "google-sub-inactividad", "inactividad@ejemplo.com");

        Integer inactividad = db.queryForObject(
                "SELECT max_inactive_interval FROM spring_session WHERE session_id = ?",
                Integer.class, cookieDeSesion(dispositivo));
        assertThat(inactividad).isEqualTo((int) Duration.ofHours(12).toSeconds());
    }

    private String cookieDeSesion(HttpClient dispositivo) {
        CookieManager almacen = (CookieManager) dispositivo.cookieHandler().orElseThrow();
        return almacen.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("SESSION"))
                .findFirst()
                .map(HttpCookie::getValue)
                .map(valor -> new String(
                        Base64.getUrlDecoder().decode(valor), StandardCharsets.UTF_8))
                .orElse(null);
    }
}
