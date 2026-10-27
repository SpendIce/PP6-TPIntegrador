package io.github.spendice.linkshortener.controller;

import io.github.spendice.linkshortener.controller.dto.ApiErrorResponse;
import io.github.spendice.linkshortener.service.CuentaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cierre masivo de sesiones vinculadas desde una sesión principal (issue #23). */
@RestController
class SesionesVinculadasController {

    @Autowired
    CuentaService cuentas;

    @PostMapping("/api/sesiones-vinculadas/cerrar")
    ResponseEntity<?> cerrar(HttpServletRequest request) {
        HttpSession sesion = request.getSession(false);
        Long usuarioId = sesion == null
                ? null : (Long) sesion.getAttribute(CuentaService.ATRIBUTO_USUARIO_ID);
        if (usuarioId == null || cuentas.usuarioDeSesion(usuarioId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ApiErrorResponse("NOT_AUTHENTICATED",
                            "Iniciá sesión con Google para cerrar las sesiones vinculadas."));
        }
        String tipo = (String) sesion.getAttribute(CuentaService.ATRIBUTO_SESION_TIPO);
        if (!CuentaService.TIPO_PRINCIPAL.equals(tipo)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ApiErrorResponse("SESSION_NOT_PRIMARY",
                            "Solo una sesión principal puede cerrar las sesiones vinculadas."));
        }
        cuentas.cerrarSesionesVinculadas(usuarioId);
        return ResponseEntity.noContent().build();
    }
}
