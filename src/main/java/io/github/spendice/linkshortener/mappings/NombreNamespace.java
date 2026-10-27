package io.github.spendice.linkshortener.mappings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/** Registro único compartido por namespaces actuales y reservas anteriores. */
@Entity
@Table(name = "nombre_namespace")
public class NombreNamespace {

    @Id
    @Column(name = "namespace", length = 30)
    private String namespace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(name = "grace_ends_at")
    private Instant graceEndsAt;

    protected NombreNamespace() {
    }

    public NombreNamespace(String namespace, Usuario usuario, Instant graceEndsAt) {
        this.namespace = namespace;
        this.usuario = usuario;
        this.graceEndsAt = graceEndsAt;
    }

    public void setGraceEndsAt(Instant graceEndsAt) {
        this.graceEndsAt = graceEndsAt;
    }
}
