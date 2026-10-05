package io.github.spendice.linkshortener.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "alias")
class AliasEntity {

    @Id
    @Column(name = "codigo", length = 16)
    private String codigo;

    @Column(name = "asignacion_actual_id")
    private Long asignacionActualId;

    protected AliasEntity() {
    }

    AliasEntity(String codigo) {
        this.codigo = codigo;
    }

    String getCodigo() {
        return codigo;
    }

    Long getAsignacionActualId() {
        return asignacionActualId;
    }

    void setAsignacionActualId(Long asignacionActualId) {
        this.asignacionActualId = asignacionActualId;
    }
}
