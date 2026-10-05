package io.github.spendice.linkshortener.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface AssignmentJpaRepository extends JpaRepository<AssignmentEntity, Long> {
}
