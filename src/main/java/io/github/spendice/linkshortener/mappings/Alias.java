package io.github.spendice.linkshortener.mappings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Código público reutilizable del enlace acortado. Conserva solo la
 * referencia a la asignación actual o a la última conocida (ADR 0002):
 * puede seguir apuntando a una asignación vencida hasta la próxima
 * reutilización.
 */
@Entity
@Table(name = "alias")
public class Alias {

    @Id
    @Column(name = "codigo", length = 16)
    private String codigo;

    @Column(name = "asignacion_actual_id")
    private Long asignacionActualId;

    protected Alias() {
    }

    public Alias(String codigo) {
        this.codigo = codigo;
    }

    public String getCodigo() {
        return codigo;
    }

    public Long getAsignacionActualId() {
        return asignacionActualId;
    }

    public void setAsignacionActualId(Long asignacionActualId) {
        this.asignacionActualId = asignacionActualId;
    }
}
