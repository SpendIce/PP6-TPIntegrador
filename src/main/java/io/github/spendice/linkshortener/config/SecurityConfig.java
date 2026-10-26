package io.github.spendice.linkshortener.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.spendice.linkshortener.controller.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.DelegatingAccessDeniedHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.DefaultHttpFirewall;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/**
 * Seguridad HTTP del servicio (issue #18): todo el contrato sigue siendo
 * anónimo — {@code GET /{alias}}, la página 404, la web, los estáticos y
 * las rutas reservadas no piden credenciales — y no hay sesión de
 * aplicación ({@code STATELESS}). La única exigencia es CSRF en los
 * métodos que cambian estado (doble envío): el token se obtiene con
 * {@code GET /api/csrf} y viaja en la cookie legible {@code XSRF-TOKEN};
 * el POST lo repite en el encabezado {@code X-XSRF-TOKEN}. El token no
 * depende de ninguna sesión: la web y las extensiones lo obtienen antes
 * de crear un enlace. {@code CsrfTokenRequestAttributeHandler} compara
 * el encabezado con el valor crudo de la cookie, la forma que espera un
 * cliente que lee la cookie {@code XSRF-TOKEN}.
 */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AccessDeniedHandler accessDeniedHandler) throws Exception {
        return http
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
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
     * CSRF llevan ese código; un 403 de autorización futuro (#19) usa el
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
