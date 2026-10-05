package io.github.spendice.linkshortener.application;

import io.github.spendice.linkshortener.application.port.AliasStore;
import io.github.spendice.linkshortener.application.port.AssignmentStore;
import io.github.spendice.linkshortener.domain.AliasAlphabet;
import io.github.spendice.linkshortener.domain.Assignment;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * Caso de uso de resolución: devuelve la asignación vigente del alias en
 * el instante del servidor, o vacío si el alias es desconocido o su
 * asignación ya venció. Lee destino y vencimiento de una única asignación
 * (la actual del alias), por lo que una reasignación concurrente no puede
 * mezclar datos de asignaciones distintas.
 */
public class ResolveLink {

    private final AliasStore aliases;
    private final AssignmentStore assignments;
    private final Clock clock;

    public ResolveLink(AliasStore aliases, AssignmentStore assignments, Clock clock) {
        this.aliases = aliases;
        this.assignments = assignments;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<Assignment> resolve(String code) {
        if (!AliasAlphabet.isCode(code)) {
            return Optional.empty();
        }
        return aliases.findByCode(code)
                .flatMap(alias -> alias.currentAssignment().flatMap(assignments::findById))
                .filter(assignment -> assignment.isActiveAt(clock.instant()));
    }
}
