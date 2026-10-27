package io.github.spendice.linkshortener.controller;

import io.github.spendice.linkshortener.controller.dto.ApiErrorResponse;
import io.github.spendice.linkshortener.controller.dto.ElegirNamespaceRequest;
import io.github.spendice.linkshortener.controller.dto.NamespaceSugerenciaResponse;
import io.github.spendice.linkshortener.controller.dto.SesionActualResponse;
import io.github.spendice.linkshortener.controller.dto.UsuarioSesion;
import io.github.spendice.linkshortener.mappings.Usuario;
import io.github.spendice.linkshortener.service.CuentaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Elección del namespace en el primer login (issue #20). El alta exige
 * una sesión con usuario: sin sesión responde {@code 401} con el mismo
 * formato de error del contrato, y la web solo muestra la pantalla a
 * quien inició sesión. Los rechazos de formato, nombre reservado, nombre
 * tomado y usuario que ya eligió salen del service con código propio.
 */
@RestController
class NamespaceController {

    @Autowired
    CuentaService cuentas;

    @GetMapping("/api/namespace/sugerencia")
    ResponseEntity<?> sugerencia(HttpServletRequest request) {
        HttpSession sesion = request.getSession(false);
        Usuario usuario = usuarioDe(sesion);
        if (usuario == null) {
            return sinSesion();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new NamespaceSugerenciaResponse(
                        cuentas.sugerenciaDeNamespace(usuario.getEmail())));
    }

    @PostMapping("/api/namespace")
    ResponseEntity<?> elegir(@RequestBody ElegirNamespaceRequest propuesta,
                             HttpServletRequest request) {
        HttpSession sesion = request.getSession(false);
        Usuario usuario = usuarioDe(sesion);
        if (usuario == null) {
            return sinSesion();
        }
        Usuario elegido = cuentas.elegirNamespace(usuario.getId(), propuesta.namespace());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new SesionActualResponse(new UsuarioSesion(
                        elegido.getEmail(),
                        elegido.getNamespace(),
                        CuentaService.tipoDeSesion(sesion),
                        "")));
    }

    private Usuario usuarioDe(HttpSession sesion) {
        if (sesion == null) {
            return null;
        }
        Long usuarioId = (Long) sesion.getAttribute(CuentaService.ATRIBUTO_USUARIO_ID);
        return cuentas.usuarioDeSesion(usuarioId).orElse(null);
    }

    private static ResponseEntity<ApiErrorResponse> sinSesion() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse("NOT_AUTHENTICATED",
                        "Iniciá sesión con Google para elegir tu namespace."));
    }
}
