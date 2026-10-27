package io.github.spendice.linkshortener.controller;

import io.github.spendice.linkshortener.controller.dto.SesionActualResponse;
import io.github.spendice.linkshortener.controller.dto.UsuarioSesion;
import io.github.spendice.linkshortener.service.CuentaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sesión actual del dispositivo (issue #19): lee los atributos que el
 * login dejó en la sesión persistida y devuelve el cuerpo del contrato.
 * {@code getSession(false)} evita crear una sesión al consultar sin
 * cookie: el flujo anónimo sigue sin sesión.
 */
@RestController
class SesionController {

    @Autowired
    CuentaService cuentas;

    @GetMapping("/api/sesion")
    ResponseEntity<SesionActualResponse> actual(HttpServletRequest request) {
        HttpSession sesion = request.getSession(false);
        Long usuarioId = sesion == null
                ? null : (Long) sesion.getAttribute(CuentaService.ATRIBUTO_USUARIO_ID);
        UsuarioSesion usuario = cuentas.usuarioDeSesion(usuarioId)
                .map(u -> {
                    String namespace = u.getNamespace() == null ? "" : u.getNamespace();
                    return new UsuarioSesion(
                            u.getEmail(),
                            namespace,
                            tipoDe(sesion),
                            namespace.isEmpty()
                                    ? cuentas.sugerenciaDeNamespace(u.getEmail()) : "");
                })
                .orElse(null);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new SesionActualResponse(usuario));
    }

    /** Usa principal como default para una sesión anterior sin tipo guardado. */
    private String tipoDe(HttpSession sesion) {
        String tipo = (String) sesion.getAttribute(CuentaService.ATRIBUTO_SESION_TIPO);
        return tipo == null ? CuentaService.TIPO_PRINCIPAL : tipo;
    }
}
