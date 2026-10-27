package io.github.spendice.linkshortener.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.spendice.linkshortener.controller.dto.ApiErrorResponse;
import io.github.spendice.linkshortener.mappings.Usuario;
import io.github.spendice.linkshortener.service.CuentaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.DelegatingAccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.firewall.DefaultHttpFirewall;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.session.FindByIndexNameSessionRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/**
 * Seguridad HTTP del servicio: todo el contrato sigue siendo anónimo
 * ({@code GET /{alias}}, la página 404, la web, los estáticos y las rutas
 * reservadas no piden credenciales) y la etapa 2 (issue #19, ADR 0007)
 * suma el login con Google por OIDC sobre esa misma base.
 *
 * <p>El cliente OAuth2 de Spring Security registra {@code google} como
 * único proveedor con los alcances {@code openid email profile}; las
 * credenciales llegan por {@code GOOGLE_CLIENT_ID} y
 * {@code GOOGLE_CLIENT_SECRET} sin default. La vuelta del proveedor es
 * {@code /login/oauth2/code/google}, el camino por defecto que ya está
 * registrado en Google Cloud (docs/despliegue-funnel-google.md).</p>
 *
 * <p>Las sesiones se persisten en PostgreSQL con Spring Session JDBC
 * (cookie {@code SESSION}): al completarse el login, el success handler
 * crea o reutiliza el usuario por su {@code sub} de Google y graba en la
 * sesión su id, el tipo {@code principal} y la inactividad máxima
 * configurada. Los flujos anónimos no crean sesión
 * ({@code IF_REQUIRED}): el POST de enlaces acortados y el token CSRF no
 * la necesitan (el alias personalizado del POST sí exige la sesión del
 * usuario, issue #21). El cierre es {@code POST /api/logout}, que invalida la
 * sesión del dispositivo y borra la cookie.</p>
 *
 * <p>Los métodos que cambian estado exigen CSRF por doble envío: el token
 * se obtiene con {@code GET /api/csrf} y viaja en la cookie legible
 * {@code XSRF-TOKEN}; el POST lo repite en el encabezado
 * {@code X-XSRF-TOKEN}. {@code CsrfTokenRequestAttributeHandler} compara
 * el encabezado con el valor crudo de la cookie, la forma que espera un
 * cliente que lee la cookie {@code XSRF-TOKEN}.</p>
 */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    AccessDeniedHandler accessDeniedHandler,
                                    CuentaService cuentas,
                                    CuentaProperties cuenta) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .oauth2Login(oauth2 -> oauth2
                        .successHandler(sesionPrincipal(cuentas, cuenta)))
                .logout(logout -> logout
                        .logoutUrl("/api/logout")
                        .deleteCookies("SESSION")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                // Spring Security agrega por defecto `Cache-Control: no-store` a
                // toda respuesta, incluso a los estáticos: la web y el vendor
                // del QR dejaban de cachearse. Los controladores que necesitan
                // `no-store` ya lo declaran por su cuenta (resolución, API y
                // token), así que se apaga el escritor por defecto para que los
                // estáticos respondan como antes de este Change.
                .headers(headers -> headers.cacheControl(cache -> cache.disable()))
                .exceptionHandling(handling -> handling
                        .accessDeniedHandler(accessDeniedHandler))
                .build();
    }

    /**
     * Las credenciales del registro {@code google} no tienen default
     * (issue #19): su ausencia corta el arranque nombrando la variable
     * que falta. El binding de Spring deja el placeholder sin resolver
     * como valor literal ({@code "${GOOGLE_CLIENT_ID}"}), así que la
     * validación busca valores ausentes, vacíos o con {@code ${...}}.
     */
    @Bean
    SmartInitializingSingleton credencialesGoogleObligatorias(OAuth2ClientProperties props) {
        return () -> {
            OAuth2ClientProperties.Registration google = props.getRegistration().get("google");
            validarCredencial(google == null ? null : google.getClientId(), "GOOGLE_CLIENT_ID");
            validarCredencial(google == null ? null : google.getClientSecret(),
                    "GOOGLE_CLIENT_SECRET");
        };
    }

    private static void validarCredencial(String valor, String variable) {
        if (valor == null || valor.isBlank() || valor.contains("${")) {
            throw new IllegalStateException(
                    "La credencial " + variable + " del registro OAuth2 de Google es"
                            + " obligatoria: declararla en el entorno o en el .env"
                            + " (ver .env.example); no tiene default en application.yml"
                            + " y se recibió "
                            + (valor == null ? "ausente" : "'" + valor + "'"));
        }
    }

    /**
     * Al completarse el login OIDC se materializa la sesión principal:
     * se crea el usuario en su primer login (o se reutiliza el de su
     * {@code sub}) y la sesión registra su id, su tipo {@code principal}
     * y la inactividad máxima configurada; el índice
     * {@code principal_name} deja ubicables las sesiones del usuario.
     * Termina redirigiendo a la web (o a la página pedida originalmente).
     */
    private AuthenticationSuccessHandler sesionPrincipal(CuentaService cuentas,
                                                         CuentaProperties cuenta) {
        AuthenticationSuccessHandler redireccion =
                new SavedRequestAwareAuthenticationSuccessHandler();
        return (request, response, authentication) -> {
            registrarSesionPrincipal(request, authentication, cuentas, cuenta);
            redireccion.onAuthenticationSuccess(request, response, authentication);
        };
    }

    private void registrarSesionPrincipal(HttpServletRequest request,
                                          Authentication authentication,
                                          CuentaService cuentas,
                                          CuentaProperties cuenta) {
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
        Usuario usuario = cuentas.usuarioDeLogin(oidcUser.getSubject(), oidcUser.getEmail());
        HttpSession sesion = request.getSession();
        sesion.setAttribute(CuentaService.ATRIBUTO_USUARIO_ID, usuario.getId());
        sesion.setAttribute(CuentaService.ATRIBUTO_SESION_TIPO, CuentaService.TIPO_PRINCIPAL);
        sesion.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                usuario.getId().toString());
        sesion.setMaxInactiveInterval((int) cuenta.mainSessionIdleTimeout().getSeconds());
    }

    /**
     * La etapa 1 resolvía `/abc;utm=x` como `/abc` y `//abc` como `/abc`. El
     * firewall estricto de Spring Security rechaza las dos con 400 y cambiaría
     * respuestas que el criterio pide conservar, así que se usa el firewall
     * por defecto: no bloquea rutas por su forma, y el ruteo queda acotado por
     * el patrón de alias y las rutas reservadas.
     */
    @Bean
    HttpFirewall httpFirewall() {
        return new DefaultHttpFirewall();
    }

    /**
     * Rechazo uniforme con el formato {@code {error, message}} del
     * contrato: un POST sin token CSRF válido responde 403 con el código
     * {@code CSRF_TOKEN_INVALID} de {@code openapi.yaml}. Sólo los rechazos
     * CSRF llevan ese código; un 403 de autorización futuro usa el
     * cuerpo por defecto para no mentir sobre la causa.
     */
    @Bean
    AccessDeniedHandler accessDeniedHandler(ObjectMapper json) {
        LinkedHashMap<Class<? extends AccessDeniedException>, AccessDeniedHandler> porTipo =
                new LinkedHashMap<>();
        porTipo.put(CsrfException.class, csrfRechazado(json));
        return new DelegatingAccessDeniedHandler(porTipo, new AccessDeniedHandlerImpl());
    }

    private AccessDeniedHandler csrfRechazado(ObjectMapper json) {
        return (request, response, exception) -> {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getWriter(), new ApiErrorResponse(
                    "CSRF_TOKEN_INVALID",
                    "La solicitud no trae un token CSRF válido; se obtiene con GET /api/csrf."));
        };
    }
}
