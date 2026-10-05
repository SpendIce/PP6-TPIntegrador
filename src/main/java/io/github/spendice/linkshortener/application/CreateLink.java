package io.github.spendice.linkshortener.application;

import io.github.spendice.linkshortener.application.port.AliasStore;
import io.github.spendice.linkshortener.application.port.AssignmentStore;
import io.github.spendice.linkshortener.domain.Alias;
import io.github.spendice.linkshortener.domain.Assignment;
import io.github.spendice.linkshortener.domain.DestinationValidator;
import io.github.spendice.linkshortener.domain.ExpirationPolicy;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Caso de uso de creación: valida el destino, reserva un alias y persiste
 * una asignación independiente con su vencimiento. Reserva, creación y
 * actualización de la referencia actual ocurren en una única transacción
 * (mecanismo Spring permitido por ADR 0001 para el límite transaccional).
 */
public class CreateLink {

    private final DestinationValidator destinationValidator;
    private final ExpirationPolicy expirationPolicy;
    private final AliasStore aliases;
    private final AssignmentStore assignments;
    private final Clock clock;

    public CreateLink(DestinationValidator destinationValidator,
                      ExpirationPolicy expirationPolicy,
                      AliasStore aliases,
                      AssignmentStore assignments,
                      Clock clock) {
        this.destinationValidator = destinationValidator;
        this.expirationPolicy = expirationPolicy;
        this.aliases = aliases;
        this.assignments = assignments;
        this.clock = clock;
    }

    @Transactional
    public CreatedLink create(String destination) {
        destinationValidator.validate(destination);
        Instant createdAt = clock.instant();
        Alias alias = aliases.claimNewCode();
        Assignment saved = assignments.save(
                Assignment.pending(alias.code(), destination, createdAt, expirationPolicy.expiresAt(createdAt)));
        aliases.assignCurrent(alias.code(), saved.id());
        return new CreatedLink(saved.aliasCode(), saved.destination(), saved.createdAt(), saved.expiresAt());
    }
}
