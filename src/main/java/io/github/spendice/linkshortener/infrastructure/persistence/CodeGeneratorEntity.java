package io.github.spendice.linkshortener.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Fila única con el avance de la generación secuencial de códigos.
 * Persistir el próximo índice conserva la enumeración entre reinicios
 * sin reconstruirla desde los alias existentes.
 */
@Entity
@Table(name = "generador_alias")
class CodeGeneratorEntity {

    static final short SINGLETON_ID = 1;

    @Id
    private Short id;

    @Column(name = "proximo_indice", nullable = false)
    private long proximoIndice;

    protected CodeGeneratorEntity() {
    }

    long getProximoIndice() {
        return proximoIndice;
    }

    void setProximoIndice(long proximoIndice) {
        this.proximoIndice = proximoIndice;
    }
}
