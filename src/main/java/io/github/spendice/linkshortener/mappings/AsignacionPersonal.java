package io.github.spendice.linkshortener.mappings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Asociación temporal de un alias personalizado con una URL de destino
 * (issue #21). Pertenece al usuario, no al texto del namespace: si el
 * usuario cambia de namespace, sus enlaces personales pasan a responder
 * bajo el nuevo nombre conservando su alias. El historial se conserva
 * como el de {@link Asignacion} (ADR 0003): al vencer, el mismo usuario
 * puede volver a usar el alias y cada asignación queda persistida.
 */
@Entity
@Table(name = "asignacion_personal")
public class AsignacionPersonal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usuario_id", nullable = false)
    private Long usuarioId;

    @Column(name = "alias_personalizado", nullable = false, length = 50)
    private String aliasPersonalizado;

    @Column(name = "destino", nullable = false)
    private String destino;

    @Column(name = "creada_en", nullable = false)
    private Instant creadaEn;

    @Column(name = "vence_en", nullable = false)
    private Instant venceEn;

    protected AsignacionPersonal() {
    }

    public AsignacionPersonal(Long usuarioId, String aliasPersonalizado,
                              String destino, Instant creadaEn, Instant venceEn) {
        this.usuarioId = usuarioId;
        this.aliasPersonalizado = aliasPersonalizado;
        this.destino = destino;
        this.creadaEn = creadaEn;
        this.venceEn = venceEn;
    }

    public Long getId() {
        return id;
    }

    public Long getUsuarioId() {
        return usuarioId;
    }

    public String getAliasPersonalizado() {
        return aliasPersonalizado;
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
