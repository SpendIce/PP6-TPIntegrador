package io.github.spendice.linkshortener;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * Reemplazo del reloj del sistema por un reloj mutable en las pruebas de
 * integración. {@code @TestConfiguration} queda fuera del component scan:
 * solo entra al contexto cuando una prueba la declara explícitamente.
 */
@TestConfiguration
public class ClockTestConfiguration {

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-10-05T12:00:00Z"));
    }
}
