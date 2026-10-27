package io.github.spendice.linkshortener;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.Locale;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Punto de sincronización de prueba sobre el SQL generado: armado con
 * una barrera, retiene cada {@code insert into nombre_namespace} hasta
 * que llegan todos los hilos esperados. Dos adquisiciones concurrentes
 * del mismo nombre quedan así obligadas a solaparse dentro de la sección
 * crítica: cada una pasó la verificación de disponibilidad viendo el
 * nombre libre — la otra transacción sigue abierta, detenida antes de su
 * insert — y chocan en la clave primaria, que es lo que la unicidad tiene
 * que serializar (issue #24). Sin armar, deja pasar todo el SQL intacto.
 */
public class InspeccionNombreNamespace implements StatementInspector {

    private final AtomicReference<CyclicBarrier> armada = new AtomicReference<>();

    public void armar(CyclicBarrier barrera) {
        armada.set(barrera);
    }

    public void desarmar() {
        armada.set(null);
    }

    @Override
    public String inspect(String sql) {
        CyclicBarrier barrera = armada.get();
        if (barrera != null
                && sql.toLowerCase(Locale.ROOT).contains("insert into nombre_namespace")) {
            try {
                barrera.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                throw new IllegalStateException(
                        "Las adquisiciones concurrentes no llegaron juntas al insert", e);
            }
        }
        return sql;
    }
}
