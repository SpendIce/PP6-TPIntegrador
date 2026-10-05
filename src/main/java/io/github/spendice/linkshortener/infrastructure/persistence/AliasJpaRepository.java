package io.github.spendice.linkshortener.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

interface AliasJpaRepository extends JpaRepository<AliasEntity, String> {

    /**
     * Códigos cuya asignación actual está vencida en {@code instant}:
     * candidatos a reciclaje que la política de dominio prioriza. El
     * vínculo usa la referencia actual del alias, garantizada por la FK
     * compuesta con {@code asignacion}.
     */
    @Query("""
            select al.codigo
            from AliasEntity al, AssignmentEntity a
            where a.aliasCodigo = al.codigo
              and a.id = al.asignacionActualId
              and a.venceEn <= :instant
            """)
    List<String> findRecyclableCodes(Instant instant);
}
