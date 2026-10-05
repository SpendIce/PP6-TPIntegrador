package io.github.spendice.linkshortener.infrastructure.persistence;

import io.github.spendice.linkshortener.application.port.AssignmentStore;
import io.github.spendice.linkshortener.domain.Assignment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
class JpaAssignmentStore implements AssignmentStore {

    private final AssignmentJpaRepository assignments;

    JpaAssignmentStore(AssignmentJpaRepository assignments) {
        this.assignments = assignments;
    }

    @Override
    @Transactional
    public Assignment save(Assignment assignment) {
        AssignmentEntity entity = assignments.save(new AssignmentEntity(
                assignment.aliasCode(),
                assignment.destination(),
                assignment.createdAt(),
                assignment.expiresAt()));
        return toDomain(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Assignment> findById(long id) {
        return assignments.findById(id).map(JpaAssignmentStore::toDomain);
    }

    private static Assignment toDomain(AssignmentEntity entity) {
        return new Assignment(entity.getId(), entity.getAliasCodigo(),
                entity.getDestino(), entity.getCreadaEn(), entity.getVenceEn());
    }
}
