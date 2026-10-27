package io.github.spendice.linkshortener.controller;

import io.github.spendice.linkshortener.config.CuentaProperties;
import io.github.spendice.linkshortener.controller.dto.ApiErrorResponse;
import io.github.spendice.linkshortener.controller.dto.CodigoVinculacionResponse;
import io.github.spendice.linkshortener.controller.dto.ConfirmacionVinculacionResponse;
import io.github.spendice.linkshortener.controller.dto.EstadoVinculacionResponse;
import io.github.spendice.linkshortener.controller.dto.UsuarioSesion;
import io.github.spendice.linkshortener.mappings.Usuario;
import io.github.spendice.linkshortener.service.CodigoVinculacionCreado;
import io.github.spendice.linkshortener.service.CuentaService;
import io.github.spendice.linkshortener.service.EstadoVinculacion;
import io.github.spendice.linkshortener.service.PresentacionVinculacion;
import io.github.spendice.linkshortener.service.ResultadoVinculacion;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * Vinculación por QR (issue #22, ADR 0007): el dispositivo que espera
 * pide el código con {@code POST /api/vinculacion} (anónimo: todavía no
 * inició sesión), muestra el QR con la {@code confirmacionUrl} y consulta
 * {@code GET /api/vinculacion/{codigo}} cada 2 segundos; la primera
 * consulta que encuentra el código aprobado materializa la sesión
 * vinculada en este dispositivo y lo consume. Desde la página
 * {@code /vincular/{codigo}}, una sesión principal aprueba con {@code
 * POST /api/vinculacion/{codigo}/aprobar}.
 */
@RestController
class VinculacionController {

    @Autowired
    CuentaService cuentas;

    @Autowired
    CuentaProperties cuenta;

    @PostMapping("/api/vinculacion")
    ResponseEntity<CodigoVinculacionResponse> crear() {
        CodigoVinculacionCreado creado = cuentas.crearVinculacion();
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(new CodigoVinculacionResponse(creado.codigo(),
                        creado.confirmacionUrl(), creado.venceEn()));
    }

    /**
     * Consulta del dispositivo que espera. Solo la respuesta {@code
     * aprobada} crea una sesión (los demás estados no la necesitan, y el
     * flujo anónimo sigue sin emitirla); consumir ya dejó el código
     * usado, así que repetirla responde {@code vencida}.
     */
    @GetMapping("/api/vinculacion/{codigo}")
    ResponseEntity<?> estado(@PathVariable String codigo, HttpServletRequest request) {
        HttpSession sesion = request.getSession(false);
        ResultadoVinculacion resultado = cuentas.consumirVinculacion(
                codigo, request, sesion, cuenta.linkedSessionIdleTimeout());
        return switch (resultado.estado()) {
            case INEXISTENTE -> codigoInvalido();
            case APROBADA -> {
                finalizarSesionVinculada(request, request.getSession(false), resultado.usuario());
                yield estado("aprobada");
            }
            default -> estado(resultado.estado().name().toLowerCase(Locale.ROOT));
        };
    }

    /**
     * Datos de la página de confirmación para el que aprueba: el estado
     * del código y su usuario (la página muestra el namespace o, si no
     * lo eligió, el email). Exige sesión con usuario: sin ella responde
     * {@code 401} y la página vuelve a entrar por el login. La primera
     * sesión principal que la abre liga el código a su usuario en
     * {@code presentado_a}: a otra sesión el código le responde el mismo
     * {@code 404} que a uno inválido y no lo puede aprobar.
     */
    @GetMapping("/api/vinculacion/{codigo}/confirmacion")
    ResponseEntity<?> confirmacion(@PathVariable String codigo, HttpServletRequest request) {
        HttpSession sesion = request.getSession(false);
        Usuario usuario = cuentas.usuarioDeSesion(sesion).orElse(null);
        if (usuario == null) {
            return sinSesion();
        }
        PresentacionVinculacion presentacion = cuentas.presentarVinculacion(codigo,
                usuario.getId(),
                CuentaService.TIPO_PRINCIPAL.equals(CuentaService.tipoDeSesion(sesion)));
        if (presentacion == PresentacionVinculacion.INVALIDO
                || presentacion == PresentacionVinculacion.PRESENTADA_A_OTRO) {
            return codigoInvalido();
        }
        String namespace = usuario.getNamespace() == null ? "" : usuario.getNamespace();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ConfirmacionVinculacionResponse(
                        presentacion.name().toLowerCase(Locale.ROOT),
                        new UsuarioSesion(usuario.getEmail(), namespace,
                                CuentaService.tipoDeSesion(sesion),
                                namespace.isEmpty()
                                        ? cuentas.sugerenciaDeNamespace(usuario.getEmail()) : "")));
    }

    /**
     * Aprobación explícita: la sesión debe ser principal; desde una
     * vinculada se rechaza con {@code NOT_PRINCIPAL_SESSION}. Solo un
     * código pendiente y vigente presentado a esta sesión queda
     * aprobado: presentado a otra, sin presentación previa, vencido,
     * usado o inexistente responde el mismo {@code 404} y no abre
     * ninguna sesión.
     */
    @PostMapping("/api/vinculacion/{codigo}/aprobar")
    ResponseEntity<?> aprobar(@PathVariable String codigo, HttpServletRequest request) {
        HttpSession sesion = request.getSession(false);
        Usuario usuario = cuentas.usuarioDeSesion(sesion).orElse(null);
        if (usuario == null) {
            return sinSesion();
        }
        if (!CuentaService.TIPO_PRINCIPAL.equals(CuentaService.tipoDeSesion(sesion))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .cacheControl(CacheControl.noStore())
                    .body(new ApiErrorResponse("NOT_PRINCIPAL_SESSION",
                            "Solo una sesión principal puede aprobar una vinculación por QR."));
        }
        if (!cuentas.aprobarVinculacion(codigo, usuario.getId())) {
            return codigoInvalido();
        }
        return estado("aprobada");
    }

    /**
     * Completa la sesión vinculada del dispositivo que esperaba. Corre
     * dentro de la misma transacción del pedido que el consumo y el
     * guardado que Spring Session hace al cerrar la respuesta: aquí rota
     * el id al otorgar acceso y registra el {@code SecurityContext} para
     * que las rutas con sesión ({@code /vincular/**}) la reconozcan.
     */
    private void finalizarSesionVinculada(HttpServletRequest request, HttpSession sesion,
                                          Usuario usuario) {
        request.changeSessionId();
        SecurityContext contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                usuario.getId(), null, AuthorityUtils.createAuthorityList("ROLE_USER")));
        sesion.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                contexto);
    }

    private static ResponseEntity<EstadoVinculacionResponse> estado(String estado) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new EstadoVinculacionResponse(estado));
    }

    private static ResponseEntity<ApiErrorResponse> sinSesion() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse("NOT_AUTHENTICATED",
                        "Iniciá sesión con Google para aprobar una vinculación."));
    }

    private static ResponseEntity<ApiErrorResponse> codigoInvalido() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse("LINK_CODE_INVALID",
                        "El código de vinculación no existe o ya no sirve; pedí uno nuevo."));
    }
}
