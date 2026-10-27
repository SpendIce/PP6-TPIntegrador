package io.github.spendice.linkshortener.controller;

import io.github.spendice.linkshortener.controller.dto.ApiErrorResponse;
import io.github.spendice.linkshortener.controller.dto.CreateLinkRequest;
import io.github.spendice.linkshortener.service.AcortadorService;
import io.github.spendice.linkshortener.service.CuentaService;
import io.github.spendice.linkshortener.service.EnlaceCreado;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entrada HTTP de la creación: pasa el destino al service y devuelve el
 * resultado como cuerpo del contrato, sin lógica propia (pass-through).
 * Con {@code aliasPersonalizado} (issue #21) el enlace es personal del
 * usuario de la sesión: sin sesión responde {@code 401} con el formato
 * de error del contrato, y los rechazos de formato, namespace pendiente
 * y alias vigente salen del service con código propio.
 */
@RestController
class LinkApiController {

    @Autowired
    AcortadorService service;

    @Autowired
    CuentaService cuentas;

    @PostMapping("/api/links")
    ResponseEntity<?> crear(@RequestBody CreateLinkRequest request,
                            HttpServletRequest http) {
        String propuesta = request.aliasPersonalizado();
        if (propuesta == null || propuesta.isEmpty()) {
            return creado(service.acortar(request.destination()));
        }
        Long usuarioId = usuarioIdDe(http.getSession(false));
        if (usuarioId == null || cuentas.usuarioDeSesion(usuarioId).isEmpty()) {
            return sinSesion();
        }
        return creado(service.crearEnlacePersonal(usuarioId, propuesta,
                request.destination()));
    }

    private static Long usuarioIdDe(HttpSession sesion) {
        return sesion == null
                ? null : (Long) sesion.getAttribute(CuentaService.ATRIBUTO_USUARIO_ID);
    }

    private static ResponseEntity<EnlaceCreado> creado(EnlaceCreado enlace) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(enlace);
    }

    private static ResponseEntity<ApiErrorResponse> sinSesion() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse("NOT_AUTHENTICATED",
                        "Iniciá sesión con Google para crear enlaces personales."));
    }
}
