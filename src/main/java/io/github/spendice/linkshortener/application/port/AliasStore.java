package io.github.spendice.linkshortener.application.port;

import io.github.spendice.linkshortener.domain.Alias;

import java.util.Optional;

/**
 * Puerta de salida hacia el registro de alias. Las operaciones se
 * invocan dentro de la transacción del caso de uso: la reserva del
 * código, la creación de la asignación y la actualización de la
 * referencia actual forman una unidad atómica.
 */
public interface AliasStore {

    /**
     * Reserva atómicamente el próximo código nuevo según la política de
     * selección vigente y devuelve el alias persistido sin asignación
     * actual. Nunca devuelve un código ya emitido ni uno reservado.
     */
    Alias claimNewCode();

    Optional<Alias> findByCode(String code);

    /** Actualiza la referencia a la asignación actual del alias. */
    void assignCurrent(String code, long assignmentId);
}
