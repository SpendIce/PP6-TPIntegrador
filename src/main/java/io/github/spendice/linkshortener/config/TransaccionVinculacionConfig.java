package io.github.spendice.linkshortener.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Atomicidad del consumo de la vinculación por QR (issue #22): la
 * consulta del dispositivo que espera marca el código {@code CONSUMIDA}
 * y Spring Session graba la sesión vinculada recién al cerrar la
 * respuesta, cuando el service ya devolvió. Para que las dos escrituras
 * sean una sola transacción —si falla la persistencia de la sesión, el
 * consumo también revierte y el código sigue {@code APROBADA} para que
 * la próxima consulta reintente— la solicitud de la API de vinculación
 * corre dentro de una transacción que abre este filtro antes de
 * {@code SessionRepositoryFilter} (que es quien persiste la sesión al
 * final del pedido) y confirma después.
 *
 * <p>Solo aplica a {@code /api/vinculacion} y {@code /api/vinculacion/*}:
 * el resto del contrato no toca la tabla de sesiones dentro del service
 * y no necesita una transacción por solicitud.</p>
 */
@Configuration
class TransaccionVinculacionConfig {

    @Bean
    FilterRegistrationBean<SolicitudTransaccional> transaccionApiVinculacion(
            TransactionTemplate transacciones) {
        FilterRegistrationBean<SolicitudTransaccional> registro = new FilterRegistrationBean<>();
        registro.setFilter(new SolicitudTransaccional(transacciones));
        registro.addUrlPatterns("/api/vinculacion", "/api/vinculacion/*");
        // Antes de todos los filtros: la transacción tiene que seguir
        // abierta cuando SessionRepositoryFilter graba la sesión.
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registro;
    }

    /**
     * Envuelve el pedido en una transacción: corre la cadena de filtros
     * dentro y confirma al volver; cualquier excepción —incluida la del
     * guardado de la sesión al cerrar la respuesta— la revierte entera.
     */
    static class SolicitudTransaccional extends OncePerRequestFilter {

        private final TransactionTemplate transacciones;

        SolicitudTransaccional(TransactionTemplate transacciones) {
            this.transacciones = transacciones;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            try {
                transacciones.executeWithoutResult(status -> {
                    try {
                        chain.doFilter(request, response);
                    } catch (IOException | ServletException e) {
                        throw new CadenaInterrumpida(e);
                    }
                });
            } catch (CadenaInterrumpida e) {
                // La transacción ya revirtió: vuelve la excepción original.
                Throwable causa = e.getCause();
                if (causa instanceof ServletException servletException) {
                    throw servletException;
                }
                throw (IOException) causa;
            }
        }

        /** El {@code try} del template no admite excepciones checked. */
        private static final class CadenaInterrumpida extends RuntimeException {

            CadenaInterrumpida(Throwable causa) {
                super(causa);
            }
        }
    }
}
