package io.github.spendice.linkshortener.service;

import io.github.spendice.linkshortener.mappings.Usuario;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Service del área de cuentas (ADR 0008): concentra los casos de uso de
 * usuarios, namespaces y sesiones con el mismo estilo que
 * {@link AcortadorService}: {@code EntityManager} con HQL directo sobre
 * las entities de {@code mappings}, reglas como helpers privados e
 * inyección con {@code @Autowired} en los campos.
 *
 * <p>El inicio de sesión con Google ({@link #usuarioDeLogin}) lo llama el
 * success handler de la cadena de seguridad; la sesión actual
 * ({@link #usuarioDeSesion}) alimenta el endpoint {@code GET
 * /api/sesion}.</p>
 */
@Service
public class CuentaService {

    /**
     * Atributo de sesión con el id del usuario que inició sesión. Lo
     * escribe el success handler del login y lo lee {@code GET
     * /api/sesion}.
     */
    public static final String ATRIBUTO_USUARIO_ID = "usuarioId";

    /**
     * Atributo de sesión con el tipo de sesión («principal» hoy; la
     * vinculación por QR registrará «vinculada»).
     */
    public static final String ATRIBUTO_SESION_TIPO = "sesionTipo";

    /** Tipo de la sesión abierta con login directo de Google. */
    public static final String TIPO_PRINCIPAL = "principal";

    @Autowired
    EntityManager em;

    /**
     * Caso de uso del login: devuelve el usuario de la identidad de
     * Google recibida, creándolo en su primer login. El {@code sub} es la
     * identidad única; el email se conserva actualizado si Google lo
     * cambió desde el login anterior. La unicidad la garantiza la
     * restricción {@code uq_usuario_google_sub} del esquema.
     */
    @Transactional
    public Usuario usuarioDeLogin(String googleSub, String email) {
        Usuario usuario = em.createQuery(
                "select u from Usuario u where u.googleSub = :sub", Usuario.class)
                .setParameter("sub", googleSub)
                .getResultStream()
                .findFirst()
                .orElse(null);
        if (usuario == null) {
            usuario = new Usuario(googleSub, email);
            em.persist(usuario);
        } else if (email != null && !email.equals(usuario.getEmail())) {
            usuario.setEmail(email);
        }
        return usuario;
    }

    /**
     * Usuario al que pertenece una sesión: vacío si no hay sesión o si la
     * sesión no tiene usuario (todavía no completó el login).
     */
    @Transactional(readOnly = true)
    public Optional<Usuario> usuarioDeSesion(Long usuarioId) {
        if (usuarioId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(em.find(Usuario.class, usuarioId));
    }
}
