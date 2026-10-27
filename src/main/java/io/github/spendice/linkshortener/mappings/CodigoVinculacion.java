package io.github.spendice.linkshortener.mappings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Código de vinculación por QR (issue #22): lo pide el dispositivo que
 * espera y lo codifica el QR dentro de la URL de confirmación. Es de un
 * solo uso y vence a los 60 segundos ({@code cuenta.link-code-ttl}): el
 * vencimiento se evalúa en el servidor al consultarlo, sin tarea de
 * limpieza.
 *
 * <p>El ciclo es {@code PENDIENTE} → {@code APROBADA} → {@code
 * CONSUMIDA}: la primera sesión principal que abre la confirmación liga
 * el código a su usuario en {@code presentadoA}, y solo ese usuario
 * puede aprobarlo (otra sesión principal o una aprobación sin
 * presentación previa se rechazan); el que aprobó queda en {@code
 * usuarioId}, y la consulta del dispositivo que espera lo consume y abre
 * la sesión vinculada. Un código vencido o consumido no abre ninguna
 * sesión.</p>
 */
@Entity
@Table(name = "codigo_vinculacion")
public class CodigoVinculacion {

    /** Estado persistido del ciclo de vida del código. */
    public enum Estado {
        PENDIENTE,
        APROBADA,
        CONSUMIDA
    }

    @Id
    @Column(name = "codigo", length = 64)
    private String codigo;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 16)
    private Estado estado;

    @Column(name = "creada_en", nullable = false)
    private Instant creadaEn;

    @Column(name = "vence_en", nullable = false)
    private Instant venceEn;

    /** Usuario que aprobó desde su sesión principal; vacío en pendiente. */
    @Column(name = "usuario_id")
    private Long usuarioId;

    /**
     * Usuario al que se presentó la confirmación: la primera sesión
     * principal que la abre lo liga acá y solo él puede aprobar el
     * código. Vacío hasta la primera presentación.
     */
    @Column(name = "presentado_a")
    private Long presentadoA;

    protected CodigoVinculacion() {
    }

    public CodigoVinculacion(String codigo, Instant creadaEn, Instant venceEn) {
        this.codigo = codigo;
        this.estado = Estado.PENDIENTE;
        this.creadaEn = creadaEn;
        this.venceEn = venceEn;
    }

    public String getCodigo() {
        return codigo;
    }

    public Estado getEstado() {
        return estado;
    }

    public void setEstado(Estado estado) {
        this.estado = estado;
    }

    public Instant getCreadaEn() {
        return creadaEn;
    }

    public Instant getVenceEn() {
        return venceEn;
    }

    public Long getUsuarioId() {
        return usuarioId;
    }

    public void setUsuarioId(Long usuarioId) {
        this.usuarioId = usuarioId;
    }

    public Long getPresentadoA() {
        return presentadoA;
    }

    public void setPresentadoA(Long presentadoA) {
        this.presentadoA = presentadoA;
    }
}
