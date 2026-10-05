package io.github.spendice.linkshortener.domain;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * Política de reciclaje de alias: entre los cuya asignación actual ya
 * venció, elige el de menor longitud; a igualdad de longitud desempata
 * por el orden natural (lexicográfico) del código, que distingue
 * mayúsculas de minúsculas y es determinista (ADR 0002).
 *
 * <p>Esta política solo decide qué candidato conviene; la reserva
 * atómica del código elegido es responsabilidad del mecanismo
 * transaccional del adaptador de persistencia.</p>
 */
public final class AliasRecycling {

    /**
     * Prioridad de reciclaje: menor longitud primero; desempate por
     * orden natural del código.
     */
    private static final Comparator<String> RECYCLING_PRIORITY =
            Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder());

    private AliasRecycling() {
    }

    /**
     * Alias preferido para reutilizar entre los códigos cuya asignación
     * actual está vencida, o vacío si no hay ninguno reutilizable.
     */
    public static Optional<String> chooseRecyclable(Collection<String> expiredCodes) {
        return expiredCodes.stream().min(RECYCLING_PRIORITY);
    }
}
