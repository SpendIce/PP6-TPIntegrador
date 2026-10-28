package io.github.spendice.linkshortener.mappings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/** Namespace que un usuario dejó y puede recuperar hasta el fin de gracia. */
@Entity
@Table(name = "namespace_anterior")
public class NamespaceAnterior {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(name = "namespace", nullable = false, length = 30, unique = true)
    private String namespace;

    @Column(name = "grace_ends_at", nullable = false)
    private Instant graceEndsAt;

    protected NamespaceAnterior() {
    }

    public NamespaceAnterior(Usuario usuario, String namespace, Instant graceEndsAt) {
        this.usuario = usuario;
        this.namespace = namespace;
        this.graceEndsAt = graceEndsAt;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public String getNamespace() {
        return namespace;
    }

    public Instant getGraceEndsAt() {
        return graceEndsAt;
    }
}
