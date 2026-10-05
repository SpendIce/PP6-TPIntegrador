package io.github.spendice.linkshortener.infrastructure.config;

import io.github.spendice.linkshortener.application.CreateLink;
import io.github.spendice.linkshortener.application.ResolveLink;
import io.github.spendice.linkshortener.application.port.AliasStore;
import io.github.spendice.linkshortener.application.port.AssignmentStore;
import io.github.spendice.linkshortener.domain.AliasSequence;
import io.github.spendice.linkshortener.domain.DestinationValidator;
import io.github.spendice.linkshortener.domain.ExpirationPolicy;
import io.github.spendice.linkshortener.domain.ServiceOrigin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Cableado del dominio y los casos de uso: los objetos de dominio y los
 * puertos se construyen aquí, con la configuración del despliegue.
 */
@Configuration
class DomainConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ExpirationPolicy expirationPolicy(ShortenerProperties properties) {
        return new ExpirationPolicy(properties.linkDuration());
    }

    @Bean
    AliasSequence aliasSequence(ShortenerProperties properties) {
        return new AliasSequence(properties.reservedRoutes());
    }

    @Bean
    DestinationValidator destinationValidator(ShortenerProperties properties) {
        return new DestinationValidator(ownOriginsOf(properties));
    }

    /**
     * Orígenes propios: la dirección pública configurada, los orígenes
     * declarados y los equivalentes loopback del puerto público
     * ({@code localhost}, {@code 127.0.0.1} y {@code ::1}), que en la
     * demo LAN apuntan al mismo servicio.
     */
    private Set<ServiceOrigin> ownOriginsOf(ShortenerProperties properties) {
        Set<ServiceOrigin> origins = new LinkedHashSet<>();
        ServiceOrigin publicOrigin = ServiceOrigin.of(properties.publicBaseUrl());
        origins.add(publicOrigin);
        for (String loopback : List.of("localhost", "127.0.0.1", "::1")) {
            origins.add(new ServiceOrigin(loopback, publicOrigin.port()));
        }
        for (String own : properties.ownOrigins()) {
            if (own != null && !own.isBlank()) {
                origins.add(ServiceOrigin.of(URI.create(own.trim())));
            }
        }
        return origins;
    }

    @Bean
    CreateLink createLink(DestinationValidator destinationValidator,
                          ExpirationPolicy expirationPolicy,
                          AliasStore aliases,
                          AssignmentStore assignments,
                          Clock clock) {
        return new CreateLink(destinationValidator, expirationPolicy, aliases, assignments, clock);
    }

    @Bean
    ResolveLink resolveLink(AliasStore aliases, AssignmentStore assignments, Clock clock) {
        return new ResolveLink(aliases, assignments, clock);
    }
}
