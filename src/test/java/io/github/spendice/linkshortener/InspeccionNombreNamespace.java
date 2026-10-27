package io.github.spendice.linkshortener;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.Locale;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Puntos de sincronización de prueba sobre el SQL generado (issue #24).
 *
 * <p>Con una barrera armada, retiene cada {@code insert into
 * nombre_namespace} hasta que llegan todos los hilos esperados: dos
 * adquisiciones concurrentes del mismo nombre quedan obligadas a
 * solaparse dentro de la sección crítica y chocan en la clave primaria.
 * Sin armar, deja pasar todo el SQL intacto.</p>
 *
 * <p>Con una {@link Pausa} armada, detiene la primera lectura de la
 * entidad {@code NombreNamespace} («from nombre_namespace» con
 * {@code grace_ends_at}) hasta que la prueba la libera. Así se fuerza el
 * intercalado en el que otra transacción confirma la adopción de un
 * nombre justo entre la verificación de disponibilidad y la escritura
 * del que lo intenta tomar.</p>
 */
public class InspeccionNombreNamespace implements StatementInspector {

    private final AtomicReference<CyclicBarrier> armada = new AtomicReference<>();
    private final AtomicReference<Pausa> pausa = new AtomicReference<>();

    public void armar(CyclicBarrier barrera) {
        armada.set(barrera);
    }

    public void desarmar() {
        armada.set(null);
    }

    /** Pausa la próxima lectura de un {@code NombreNamespace}. */
    public Pausa pausarAntesDeLeerNombre() {
        Pausa nueva = new Pausa();
        pausa.set(nueva);
        return nueva;
    }

    public void desarmarPausa() {
        pausa.set(null);
    }

    @Override
    public String inspect(String sql) {
        String minusculas = sql.toLowerCase(Locale.ROOT);
        Pausa espera = pausa.get();
        if (espera != null && minusculas.contains("from nombre_namespace")
                && minusculas.contains("grace_ends_at")) {
            espera.detener();
        }
        CyclicBarrier barrera = armada.get();
        if (barrera != null && minusculas.contains("insert into nombre_namespace")) {
            try {
                barrera.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                throw new IllegalStateException(
                        "Las adquisiciones concurrentes no llegaron juntas al insert", e);
            }
        }
        return sql;
    }

    /** Una sola lectura queda detenida; la prueba la libera cuando quiere. */
    public static final class Pausa {

        private final CountDownLatch llego = new CountDownLatch(1);
        private final CountDownLatch liberar = new CountDownLatch(1);
        private final AtomicBoolean usada = new AtomicBoolean();

        private void detener() {
            if (!usada.compareAndSet(false, true)) {
                return;
            }
            llego.countDown();
            try {
                if (!liberar.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "La prueba no liberó la lectura detenida de nombre_namespace");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Lectura detenida interrumpida", e);
            }
        }

        public boolean esperarLlegada() throws InterruptedException {
            return llego.await(20, TimeUnit.SECONDS);
        }

        public void liberar() {
            liberar.countDown();
        }
    }
}
