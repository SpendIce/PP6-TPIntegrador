package io.github.spendice.linkshortener.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Página de confirmación de la vinculación por QR (issue #22): es la URL
 * que codifica el QR y abre el celular. La página es estática
 * ({@code vincular.html}); el código viaja en la ruta y la página lo lee
 * para consultar {@code /api/vinculacion/{codigo}/confirmacion}. La
 * cadena de seguridad exige sesión para llegar acá: sin ella redirige al
 * login con Google y vuelve a esta misma dirección al completarse.
 */
@Controller
class VincularPageController {

    @GetMapping("/vincular/{codigo}")
    String pagina() {
        return "forward:/vincular.html";
    }
}
