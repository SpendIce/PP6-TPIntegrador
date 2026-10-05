package io.github.spendice.linkshortener.infrastructure.persistence;

import io.github.spendice.linkshortener.application.port.AliasStore;
import io.github.spendice.linkshortener.domain.Alias;
import io.github.spendice.linkshortener.domain.AliasSequence;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Implementación JPA del registro de alias. El mecanismo de reserva es
 * una coordinación transaccional única: bloquea la fila del generador,
 * pide a la política de dominio el próximo código disponible y persiste
 * el alias. La política de selección no conoce este mecanismo.
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
    public Alias claimNewCode() {
        CodeGeneratorEntity counter = generator.findById(CodeGeneratorEntity.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("Falta la fila del generador de alias"));
        long index = sequence.indexOfNextCode(counter.getProximoIndice());
        counter.setProximoIndice(index + 1);
        String code = sequence.codeAt(index);
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
