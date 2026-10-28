package io.github.spendice.linkshortener.controller;

import io.github.spendice.linkshortener.service.AcortadorService;
import io.github.spendice.linkshortener.service.AvisoNamespaceAnterior;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Resolución pública: {@code GET /{alias}} redirige (302) al destino de
 * la asignación vigente o responde 404 con la página acordada, y
 * {@code GET /{namespace}/{aliasPersonalizado}} hace lo mismo con el
 * enlace personal del usuario (issue #21) salvo que el nombre sea un
 * namespace anterior en gracia (issue #25): entonces responde 200 con la
 * página de aviso que anuncia la nueva dirección y redirige sola tras la
 * demora configurada. El patrón de un segmento solo admite símbolos del
 * alfabeto configurado (placeholder
 * {@code shortener.alias-route-pattern}, cuyo default vive en
 * {@code application.yml}) y el de dos letras, dígitos y guiones: las
 * rutas propias (archivos estáticos con extensión, {@code /api/...})
 * nunca entran aquí como alias.
 */
@RestController
class RedirectController {

    private static final String NOT_FOUND_PAGE = """
            <!DOCTYPE html>
            <html lang="es">
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Enlace no disponible</title>
            </head>
            <body>
                <main>
                    <h1>Este enlace no existe o venció</h1>
                    <p>El enlace que buscás no existe o su vigencia terminó.</p>
                </main>
            </body>
            </html>
            """;

    /** La hora límite se muestra en UTC: el servidor no conoce la zona del visitante. */
    private static final DateTimeFormatter HORA_UTC =
            DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    @Autowired
    AcortadorService service;

    @Autowired
    Clock clock;

    @GetMapping("/{alias:${shortener.alias-route-pattern}}")
    ResponseEntity<String> resolver(@PathVariable("alias") String alias) {
        return service.resolver(alias)
                .map(asignacion -> redirigir(asignacion.getDestino()))
                .orElseGet(RedirectController::noEncontrado);
    }

    /**
     * Resolución de enlaces personales (issues #21 y #25):
     * {@code GET /{namespace}/{aliasPersonalizado}} busca primero entre
     * los namespaces actuales y redirige al destino de la asignación
     * vigente; si el nombre no es actual, busca entre los anteriores en
     * gracia y sirve la página de aviso con la nueva dirección, la hora
     * límite y la demora configurada; en cualquier otro caso responde el
     * mismo 404 que el alias de un segmento. El patrón acota ambos
     * segmentos a letras, dígitos y guiones — los alfabetos del namespace
     * y del alias personalizado — para que los archivos con extensión
     * ({@code /vendor/qrcode.js}) sigan llegando al manejador de
     * estáticos; las rutas literales como {@code /api/csrf} ganan por
     * ser más específicas.
     */
    @GetMapping("/{namespace:[a-zA-Z0-9-]+}/{aliasPersonalizado:[a-zA-Z0-9-]+}")
    ResponseEntity<String> resolverPersonal(
            @PathVariable("namespace") String namespace,
            @PathVariable("aliasPersonalizado") String aliasPersonalizado) {
        return service.resolverEnlacePersonal(namespace, aliasPersonalizado)
                .map(asignacion -> redirigir(asignacion.getDestino()))
                .orElseGet(() -> service
                        .avisoDeEnlacePersonalAnterior(namespace, aliasPersonalizado)
                        .map(this::aviso)
                        .orElseGet(RedirectController::noEncontrado));
    }

    private static ResponseEntity<String> redirigir(String destino) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, destino)
                .cacheControl(CacheControl.noStore())
                .build();
    }

    private ResponseEntity<String> aviso(AvisoNamespaceAnterior aviso) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_HTML)
                .body(paginaAviso(aviso));
    }

    /**
     * La página de aviso lleva la nueva dirección completa, la hora
     * límite (la primera entre el fin de la gracia y el vencimiento del
     * enlace) y la demora en segundos y en ISO-8601. «Ir ahora» es un
     * enlace real a la nueva dirección, que conduce al destino sin
     * JavaScript; el {@code meta refresh} y la cuenta regresiva repiten
     * la redirección automática tras la demora.
     */
    private String paginaAviso(AvisoNamespaceAnterior aviso) {
        long segundos = aviso.demora().getSeconds();
        String nueva = aviso.nuevaDireccion();
        Duration restante = Duration.between(clock.instant(), aviso.horaLimite());
        String falta = restante.toMinutes() >= 1
                ? "en " + restante.toMinutes() + " min"
                : "en menos de un minuto";
        return """
                <!DOCTYPE html>
                <html lang="es">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <meta http-equiv="refresh" content="%d;url=%s">
                    <title>Esta dirección cambió</title>
                    <style>
                        body { font-family: system-ui, sans-serif; margin: 2rem auto;
                               max-width: 36rem; padding: 0 1rem; }
                        a#ir-ahora { display: inline-block; padding: .6rem 1.2rem;
                               background: #1d4ed8; color: #fff; border-radius: .4rem;
                               text-decoration: none; }
                        button { padding: .6rem 1.2rem; }
                    </style>
                </head>
                <body data-demora="%s">
                    <main>
                        <h1>Esta dirección cambió</h1>
                        <p>Esta dirección deja de funcionar a las
                            <time id="limite" datetime="%s">%s</time> (%s).
                            La nueva dirección es
                            <a id="nueva" href="%s">%s</a>.</p>
                        <p>Serás redirigido automáticamente en
                            <span id="cuenta">%d</span> segundos.</p>
                        <p>
                            <a id="ir-ahora" href="%s">Ir ahora</a>
                            <button id="copiar" type="button">Copiar la nueva dirección</button>
                        </p>
                    </main>
                    <script>
                        (function () {
                            var destino = document.getElementById("ir-ahora").href;
                            var cuenta = document.getElementById("cuenta");
                            var restante = %d;
                            var reloj = setInterval(function () {
                                restante -= 1;
                                cuenta.textContent = restante;
                                if (restante <= 0) {
                                    clearInterval(reloj);
                                    window.location.href = destino;
                                }
                            }, 1000);
                            document.getElementById("copiar").addEventListener("click", function () {
                                var boton = this;
                                var copiar = navigator.clipboard
                                        ? navigator.clipboard.writeText(destino)
                                        : Promise.reject();
                                copiar.then(function () {
                                    boton.textContent = "Copiada";
                                }, function () {
                                    window.prompt("Copiá la nueva dirección:", destino);
                                });
                            });
                        }());
                    </script>
                </body>
                </html>
                """.formatted(segundos, nueva, aviso.demora(), aviso.horaLimite(),
                        HORA_UTC.format(aviso.horaLimite()), falta,
                        nueva, nueva, segundos, nueva, segundos);
    }

    private static ResponseEntity<String> noEncontrado() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_HTML)
                .body(NOT_FOUND_PAGE);
    }
}
