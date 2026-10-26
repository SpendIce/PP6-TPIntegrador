package io.github.spendice.linkshortener.controller;

import io.github.spendice.linkshortener.controller.dto.CsrfTokenResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entrega del token CSRF del contrato (issue #18): resolver el
 * {@code CsrfToken} escribe la cookie {@code XSRF-TOKEN} en la respuesta
 * y el cuerpo expone el mismo valor para los clientes que no pueden leer
 * la cookie (las extensiones). Sin estado de sesión: cada cliente
 * obtiene su token con este GET antes del primer POST.
 */
@RestController
class CsrfController {

    @GetMapping("/api/csrf")
    ResponseEntity<CsrfTokenResponse> token(CsrfToken csrfToken) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CsrfTokenResponse(csrfToken.getToken()));
    }
}
