package io.github.spendice.linkshortener.controller;

import io.github.spendice.linkshortener.service.AcortadorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resolución pública: {@code GET /{alias}} redirige (302) al destino de
 * la asignación vigente o responde 404 con la página acordada, y
 * {@code GET /{namespace}/{aliasPersonalizado}} hace lo mismo con el
 * enlace personal del usuario (issue #21). El patrón de un segmento solo
 * admite símbolos del alfabeto configurado (placeholder
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

    @Autowired
    AcortadorService service;

    @GetMapping("/{alias:${shortener.alias-route-pattern}}")
    ResponseEntity<String> resolver(@PathVariable("alias") String alias) {
        return service.resolver(alias)
                .map(asignacion -> ResponseEntity.status(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, asignacion.getDestino())
                        .cacheControl(CacheControl.noStore())
                        .<String>build())
                .orElseGet(RedirectController::noEncontrado);
    }

    /**
     * Resolución de enlaces personales (issue #21):
     * {@code GET /{namespace}/{aliasPersonalizado}} redirige al destino
     * de la asignación vigente del usuario o responde el mismo 404 que
     * el alias de un segmento. El patrón acota ambos segmentos a letras,
     * dígitos y guiones — los alfabetos del namespace y del alias
     * personalizado — para que los archivos con extensión
     * ({@code /vendor/qrcode.js}) sigan llegando al manejador de
     * estáticos; las rutas literales como {@code /api/csrf} ganan por
     * ser más específicas.
     */
    @GetMapping("/{namespace:[a-zA-Z0-9-]+}/{aliasPersonalizado:[a-zA-Z0-9-]+}")
    ResponseEntity<String> resolverPersonal(
            @PathVariable("namespace") String namespace,
            @PathVariable("aliasPersonalizado") String aliasPersonalizado) {
        return service.resolverEnlacePersonal(namespace, aliasPersonalizado)
                .map(asignacion -> ResponseEntity.status(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, asignacion.getDestino())
                        .cacheControl(CacheControl.noStore())
                        .<String>build())
                .orElseGet(RedirectController::noEncontrado);
    }

    private static ResponseEntity<String> noEncontrado() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_HTML)
                .body(NOT_FOUND_PAGE);
    }
}
