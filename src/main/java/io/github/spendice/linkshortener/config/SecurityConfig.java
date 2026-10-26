package io.github.spendice.linkshortener.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.spendice.linkshortener.controller.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

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
    SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper json) throws Exception {
        return http
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .exceptionHandling(handling -> handling
                        .accessDeniedHandler(csrfRechazado(json)))
                .build();
    }

    /**
     * Rechazo uniforme con el formato {@code {error, message}} del
     * contrato: un POST sin token CSRF válido responde 403 con el código
     * {@code CSRF_TOKEN_INVALID} de {@code openapi.yaml}.
     */
    private AccessDeniedHandler csrfRechazado(ObjectMapper json) {
        return (request, response, exception) -> {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getWriter(), new ApiErrorResponse(
                    "CSRF_TOKEN_INVALID",
                    "La solicitud no trae un token CSRF válido; se obtiene con GET /api/csrf."));
        };
    }
}
