package io.github.spendice.linkshortener;

import io.github.spendice.linkshortener.service.CuentaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Primer login simultáneo de la misma identidad (issue #19) sobre PostgreSQL
 * real: los dos disparan la creación y la restricción {@code
 * uq_usuario_google_sub} deja pasar a uno. El otro tiene que releer al que ganó
 * y completar su login, no devolver un error del callback.
 */
class CuentaServiceConcurrenciaTest extends HttpApiFixture {

    private static final String SUB = "google-sub-carrera";
    private static final String EMAIL = "carrera@ejemplo.com";

    @Autowired
    CuentaService cuentas;

    @Test
    void dosPrimerosLoginsSimultaneosTerminanEnElMismoUsuario() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier salida = new CyclicBarrier(2);
        Callable<Long> login = () -> {
            salida.await(10, TimeUnit.SECONDS);
            return cuentas.usuarioDeLogin(SUB, EMAIL).getId();
        };
        try {
            Future<Long> primero = pool.submit(login);
            Future<Long> segundo = pool.submit(login);

            Long idPrimero = primero.get(30, TimeUnit.SECONDS);
            Long idSegundo = segundo.get(30, TimeUnit.SECONDS);

            assertThat(idPrimero).isEqualTo(idSegundo);
            assertThat(db.queryForObject(
                    "select count(*) from usuario where google_sub = ?", Long.class, SUB))
                    .isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }
}
