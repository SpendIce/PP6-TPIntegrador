package io.github.spendice.linkshortener.application.port;

import io.github.spendice.linkshortener.domain.Alias;

import java.time.Instant;
import java.util.Optional;

/**
 * Puerta de salida hacia el registro de alias. Las operaciones se
 * invocan dentro de la transacción del caso de uso: la reserva del
 * código, la creación de la asignación y la actualización de la
 * referencia actual forman una unidad atómica.
 */
public interface AliasStore {

    /**
     * Reserva atómicamente un alias para una asignación creada en
     * {@code instant}, según la política de selección vigente: prefiere
     * un alias cuya asignación actual está vencida en ese instante y,
     * solo si no hay ninguno reutilizable, emite el próximo código nunca
     * usado ni reservado. Para un alias reciclado la referencia actual
     * sigue apuntando a la asignación vencida hasta que el caso de uso
     * la actualice dentro de la misma transacción.
     */
    Alias claim(Instant instant);

    Optional<Alias> findByCode(String code);

    /** Actualiza la referencia a la asignación actual del alias. */
    void assignCurrent(String code, long assignmentId);
}
