package io.github.spendice.linkshortener;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Un «dispositivo» de las pruebas HTTP de la vinculación por QR (issue
 * #22): un {@link HttpClient} con su propio almacén de cookies (el
 * celular y la computadora son clientes separados) sin seguir
 * redirects, más las consultas del contrato (GET y POST sin cuerpo con
 * el token CSRF del doble envío) y la lectura de la cookie SESSION.
 */
final class Dispositivos {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private Dispositivos() {
    }

    static HttpClient nuevo() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build();
    }

    static HttpResponse<String> get(HttpClient dispositivo, String baseUrl, String path)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .GET().timeout(REQUEST_TIMEOUT).build();
        return dispositivo.send(request, BodyHandlers.ofString());
    }

    /** POST sin cuerpo con el token CSRF del doble envío (issue #18). */
    static HttpResponse<String> postVacio(HttpClient dispositivo, String baseUrl,
                                          String path) throws Exception {
        String csrf = Csrf.obtener(dispositivo, baseUrl);
        HttpRequest request = Csrf.aplicar(dispositivo,
                        HttpRequest.newBuilder(URI.create(baseUrl + path)), csrf)
                .timeout(REQUEST_TIMEOUT)
                .POST(BodyPublishers.noBody())
                .build();
        return dispositivo.send(request, BodyHandlers.ofString());
    }

    /**
     * Id de la sesión persistida del dispositivo: la cookie {@code SESSION}
     * viaja codificada en base64url ({@code DefaultCookieSerializer}).
     */
    static String cookieDeSesion(HttpClient dispositivo) {
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
