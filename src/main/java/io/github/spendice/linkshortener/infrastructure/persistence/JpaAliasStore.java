package io.github.spendice.linkshortener.infrastructure.persistence;

import io.github.spendice.linkshortener.application.port.AliasStore;
import io.github.spendice.linkshortener.domain.Alias;
import io.github.spendice.linkshortener.domain.AliasRecycling;
import io.github.spendice.linkshortener.domain.AliasSequence;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Implementación JPA del registro de alias. El mecanismo de reserva es
 * una coordinación transaccional única: bloquea la fila del generador,
 * pide a la política de dominio el candidato (un alias vencido de
 * {@link AliasRecycling} o un código nuevo de {@link AliasSequence}) y
 * lo reserva. La política de selección no conoce este mecanismo.
 */
@Component
class JpaAliasStore implements AliasStore {

    private final AliasJpaRepository aliases;
    private final CodeGeneratorJpaRepository generator;
    private final AliasSequence sequence;

    JpaAliasStore(AliasJpaRepository aliases,
                  CodeGeneratorJpaRepository generator,
                  AliasSequence sequence) {
        this.aliases = aliases;
        this.generator = generator;
        this.sequence = sequence;
    }

    @Override
    @Transactional
    public Alias claim(Instant instant) {
        // El bloqueo de la fila del generador serializa toda la
        // selección: dos creaciones concurrentes no pueden reciclar el
        // mismo alias ni saltarse un vencido más corto disponible.
        CodeGeneratorEntity counter = generator.findById(CodeGeneratorEntity.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("Falta la fila del generador de alias"));
        List<String> recyclableCodes = aliases.findRecyclableCodes(instant);
        Optional<String> recycled = AliasRecycling.chooseRecyclable(recyclableCodes);
        if (recycled.isPresent()) {
            // El alias ya existe: conserva su historial y solo cambia su
            // referencia actual, sin consumir un código del generador.
            AliasEntity entity = aliases.findById(recycled.get())
                    .orElseThrow(() -> new IllegalStateException("Alias inexistente: " + recycled.get()));
            return new Alias(entity.getCodigo(), entity.getAsignacionActualId());
        }
        long index = sequence.indexOfNextCode(counter.getProximoIndice());
        counter.setProximoIndice(index + 1);
        String code = sequence.codeAt(index);
        // La asignación inserta de inmediato (IDENTITY) y referencia
        // alias.codigo: la fila del alias tiene que existir antes.
        aliases.saveAndFlush(new AliasEntity(code));
        return new Alias(code, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Alias> findByCode(String code) {
        return aliases.findById(code)
                .map(entity -> new Alias(entity.getCodigo(), entity.getAsignacionActualId()));
    }

    @Override
    @Transactional
    public void assignCurrent(String code, long assignmentId) {
        AliasEntity entity = aliases.findById(code)
                .orElseThrow(() -> new IllegalStateException("Alias inexistente: " + code));
        entity.setAsignacionActualId(assignmentId);
    }
}
