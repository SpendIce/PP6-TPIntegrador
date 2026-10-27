package io.github.spendice.linkshortener.mappings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Persona identificada por su cuenta de Google que inició sesión en el
 * servicio (CONTEXT.md). La identidad es el {@code sub} de Google: se
 * crea en el primer login y se reutiliza en los siguientes. El
 * {@code namespace} queda vacío hasta que el usuario lo elija; después
 * puede cambiarlo según el Change #24.
 */
@Entity
@Table(name = "usuario")
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "google_sub", nullable = false)
    private String googleSub;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "namespace", length = 30)
    private String namespace;

    protected Usuario() {
    }

    public Usuario(String googleSub, String email) {
        this.googleSub = googleSub;
        this.email = email;
    }

    public Long getId() {
        return id;
    }

    public String getGoogleSub() {
        return googleSub;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    /**
     * Namespace elegido por el usuario o {@code null} mientras no lo
     * elija: la API lo expone como cadena vacía.
     */
    public String getNamespace() {
        return namespace;
    }

    /**
     * Asigna el namespace ya normalizado (minúsculas y formato válido).
     * La unicidad sin distinguir mayúsculas la garantiza la restricción
     * {@code uq_usuario_namespace} sobre ese mismo valor normalizado.
     */
    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }
}
