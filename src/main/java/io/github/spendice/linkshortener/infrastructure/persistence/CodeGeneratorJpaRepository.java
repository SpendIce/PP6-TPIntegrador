package io.github.spendice.linkshortener.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

interface CodeGeneratorJpaRepository extends JpaRepository<CodeGeneratorEntity, Short> {

    /**
     * Toma la fila del generador con bloqueo pesimista: serializa las
     * reservas de código entre transacciones concurrentes.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CodeGeneratorEntity> findById(Short id);
}
