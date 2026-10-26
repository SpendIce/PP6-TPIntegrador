package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

/**
 * Token CSRF del contrato para las suites HTTP: {@code GET /api/csrf}
 * devuelve {@code {"token": "<valor>"}} y fija la cookie
 * {@code XSRF-TOKEN}; el POST repite ese valor en el encabezado
 * {@code X-XSRF-TOKEN} junto con la cookie (doble envío). El helper
 * comparte la maniobra entre el fixture y las suites con contextos
 * propios para no copiarla en cada suite.
 */
final class Csrf {

    static final String COOKIE = "XSRF-TOKEN";
    static final String ENCABEZADO = "X-XSRF-TOKEN";

    private static final ObjectMapper JSON = new ObjectMapper();

    private Csrf() {
    }

    /**
     * Obtiene un token con {@code GET /api/csrf}. Si el cliente tiene
     * {@code CookieManager}, la cookie queda guardada en su almacén; si
     * no, el valor devuelto alcanza para armar a mano el encabezado
     * {@code Cookie} junto con {@link #ENCABEZADO}.
     */
    static String obtener(HttpClient client, String baseUrl)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/csrf"))
                .GET().build();
        HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "GET /api/csrf respondió " + response.statusCode() + ": " + response.body());
        }
        JsonNode body = JSON.readTree(response.body());
        return body.get("token").asText();
    }

    /**
     * Encabezado {@code X-XSRF-TOKEN} y, solo si el cliente no guarda
     * cookies, también el encabezado {@code Cookie}: con
     * {@code CookieManager} la cookie ya viaja desde el almacén del
     * cliente y repetirla a mano la duplicaría.
     */
    static HttpRequest.Builder aplicar(HttpClient client, HttpRequest.Builder builder, String token) {
        builder.header(ENCABEZADO, token);
        if (client.cookieHandler().isEmpty()) {
            builder.header("Cookie", COOKIE + "=" + token);
        }
        return builder;
    }
}
