package io.github.spendice.linkshortener;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.http.HttpResponse;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rutas reservadas sobre la API HTTP con el PostgreSQL compartido del
 * fixture: el contexto se configura con códigos reservados dentro del
 * espacio ejercitado ({@code 2} de un carácter y {@code 11} de dos) para
 * demostrar que el generador nunca los emite, ni siquiera al cruzar de
 * longitud, y que al no existir como alias responden el 404 acordado.
 * La infraestructura compartida está en {@link HttpApiFixture}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "shortener.public-base-url=http://192.168.50.10:8080",
        "shortener.reserved-routes=api, error, 2, 11"
})
class RutasReservadasHttpTest extends HttpApiFixture {

    @Test
    void elGeneradorNuncaEmiteLosCodigosReservadosNiAlCruzarDeLongitud() throws Exception {
        Set<String> emitidos = new LinkedHashSet<>();
        for (int i = 0; i < 58; i++) {
            emitidos.add(createAlias("https://ejemplo.com/reserva/" + i));
        }

        // Con "2" reservado, el segundo código emitido es "3": se emite
        // un código menos de un carácter (57), nunca "2" ni "11", y el
        // primero de dos caracteres es "12" porque "11" también saltó.
        List<String> orden = List.copyOf(emitidos);
        assertThat(emitidos).hasSize(58).doesNotContain("2", "11");
        assertThat(orden.get(0)).isEqualTo("1");
        assertThat(orden.get(1)).isEqualTo("3");
        assertThat(orden.subList(0, 57)).allMatch(code -> code.length() == 1);
        assertThat(orden.get(57)).isEqualTo("12");

        // Los códigos reservados no existen como alias: el mismo 404 de
        // alias desconocido, aunque sean símbolos válidos del alfabeto.
        assertThat(get("/2").statusCode()).isEqualTo(404);
        assertThat(get("/2").body()).contains("Este enlace no existe o venció");
        assertThat(get("/11").statusCode()).isEqualTo(404);

        // El primer código de dos caracteres sí quedó emitido y resuelve.
        HttpResponse<String> redirect = get("/12");
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("Location"))
                .contains("https://ejemplo.com/reserva/57");
    }

    private String createAlias(String destination) throws Exception {
        HttpResponse<String> response = postJson("{\"destination\":\"" + destination + "\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        return body.get("alias").asText();
    }
}
