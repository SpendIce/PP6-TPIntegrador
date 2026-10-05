package io.github.spendice.linkshortener.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "asignacion")
class AssignmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alias_codigo", nullable = false, length = 16)
    private String aliasCodigo;

    @Column(name = "destino", nullable = false)
    private String destino;

    @Column(name = "creada_en", nullable = false)
    private Instant creadaEn;

    @Column(name = "vence_en", nullable = false)
    private Instant venceEn;

    protected AssignmentEntity() {
    }

    AssignmentEntity(String aliasCodigo, String destino, Instant creadaEn, Instant venceEn) {
        this.aliasCodigo = aliasCodigo;
        this.destino = destino;
        this.creadaEn = creadaEn;
        this.venceEn = venceEn;
    }

    Long getId() {
        return id;
    }

    String getAliasCodigo() {
        return aliasCodigo;
    }

    String getDestino() {
        return destino;
    }

    Instant getCreadaEn() {
        return creadaEn;
    }

    Instant getVenceEn() {
        return venceEn;
    }
}
