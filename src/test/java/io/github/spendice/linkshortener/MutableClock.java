package io.github.spendice.linkshortener;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Reloj mutable para pruebas: fija el instante que los casos de uso
 * consultan sin esperas reales de una hora. Es una fuente de tiempo de
 * test; la aplicación no expone ninguna operación pública para moverlo.
 */
public final class MutableClock extends Clock {

    private Instant instant;

    public MutableClock(Instant instant) {
        this.instant = instant;
    }

    public void set(Instant instant) {
        this.instant = instant;
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
