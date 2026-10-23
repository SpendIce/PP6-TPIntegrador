package io.github.spendice.linkshortener.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Configuración mínima del servicio: el reloj es un bean para que las
 * pruebas puedan reemplazarlo por uno mutable sin esperas reales.
 */
@Configuration
class ShortenerConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
