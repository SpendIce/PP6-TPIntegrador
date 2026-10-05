package io.github.spendice.linkshortener.application.port;

import io.github.spendice.linkshortener.domain.Assignment;

import java.util.Optional;

/**
 * Puerta de salida hacia el historial de asignaciones. Las asignaciones
 * persistidas no se modifican ni se eliminan (ADR 0003): reutilizar un
 * alias agrega una nueva asignación y mueve la referencia actual.
 */
public interface AssignmentStore {

    /** Persiste una asignación pendiente y la devuelve con identidad. */
    Assignment save(Assignment assignment);

    Optional<Assignment> findById(long id);
}
