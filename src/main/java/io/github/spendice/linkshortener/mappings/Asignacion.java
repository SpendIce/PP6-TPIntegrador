package io.github.spendice.linkshortener.mappings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Asociación temporal de un alias con una URL de destino. Tiene
 * identidad propia independiente del alias (ADR 0002): cada creación
 * produce una asignación nueva aunque repita el destino, y las
 * anteriores se conservan en el historial (ADR 0003).
 */
@Entity
@Table(name = "asignacion")
public class Asignacion {

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

    protected Asignacion() {
    }

    public Asignacion(String aliasCodigo, String destino, Instant creadaEn, Instant venceEn) {
        this.aliasCodigo = aliasCodigo;
        this.destino = destino;
        this.creadaEn = creadaEn;
        this.venceEn = venceEn;
    }

    public Long getId() {
        return id;
    }

    public String getAliasCodigo() {
        return aliasCodigo;
    }

    public String getDestino() {
        return destino;
    }

    public Instant getCreadaEn() {
        return creadaEn;
    }

    public Instant getVenceEn() {
        return venceEn;
    }
}
