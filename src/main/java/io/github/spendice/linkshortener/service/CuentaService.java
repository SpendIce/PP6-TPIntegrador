package io.github.spendice.linkshortener.service;

import io.github.spendice.linkshortener.config.CuentaProperties;
import io.github.spendice.linkshortener.mappings.Usuario;
import io.github.spendice.linkshortener.mappings.NamespaceAnterior;
import io.github.spendice.linkshortener.mappings.NombreNamespace;
import io.github.spendice.linkshortener.service.InvalidNamespaceException.Reason;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.time.Clock;
import java.time.Instant;

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

    /** Largo mínimo y máximo del namespace (issue #20). */
    private static final int NAMESPACE_MIN = 3;
    private static final int NAMESPACE_MAX = 30;

    /**
     * Solo minúsculas, dígitos y guiones; el primer y el último carácter
     * no pueden ser guion. La entrada se normaliza a minúsculas antes de
     * validar, así que «Juan» es un nombre válido que se guarda como
     * «juan».
     */
    private static final String NAMESPACE_VALIDO = "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?";

    /** Sugerencia de reemplazo cuando la parte local no llega al mínimo. */
    private static final String SUGERENCIA_POR_DEFECTO = "usuario";

    @Autowired
    EntityManager em;

    @Autowired
    TransactionTemplate transacciones;

    @Autowired
    CuentaProperties properties;

    @Autowired
    Clock clock;

    /**
     * Caso de uso del login: devuelve el usuario de la identidad de
     * Google recibida, creándolo en su primer login. El {@code sub} es la
     * identidad única; el email se conserva actualizado si Google lo
     * cambió desde el login anterior. La unicidad la garantiza la
     * restricción {@code uq_usuario_google_sub} del esquema.
     *
     * <p>Dos dispositivos que completan el primer login a la vez corren
     * esta carrera: los dos no encuentran usuario y los dos insertan. El
     * perdedor recibe la violación de la restricción única y relee el
     * usuario que ganó, en vez de devolver un error del callback. La
     * creación va en su propia transacción para que el rollback del
     * perdedor no arrastre la lectura.</p>
     */
    public Usuario usuarioDeLogin(String googleSub, String email) {
        try {
            return transacciones.execute(status -> crearOActualizar(googleSub, email));
        } catch (DataIntegrityViolationException | PersistenceException carrera) {
            // Sólo la violación de uq_usuario_google_sub es la carrera de alta
            // simultánea: cualquier otro fallo de persistencia tiene que
            // abortar el callback, no reintentarse como si fuera una colisión.
            if (!esCarreraDeAlta(carrera)) {
                throw carrera;
            }
            return transacciones.execute(status -> {
                Usuario ganador = buscarPorSub(googleSub);
                if (ganador == null) {
                    throw carrera;
                }
                return actualizarEmail(ganador, email);
            });
        }
    }

    /** ¿La excepción es la violación única de {@code google_sub}? */
    private static boolean esCarreraDeAlta(Throwable error) {
        for (Throwable actual = error; actual != null; actual = actual.getCause()) {
            if (actual instanceof ConstraintViolationException violacion
                    && "uq_usuario_google_sub".equals(violacion.getConstraintName())) {
                return true;
            }
            String mensaje = actual.getMessage();
            if (mensaje != null && mensaje.contains("uq_usuario_google_sub")) {
                return true;
            }
        }
        return false;
    }

    private Usuario crearOActualizar(String googleSub, String email) {
        Usuario usuario = buscarPorSub(googleSub);
        if (usuario == null) {
            usuario = new Usuario(googleSub, email);
            em.persist(usuario);
            em.flush();
            return usuario;
        }
        return actualizarEmail(usuario, email);
    }

    private Usuario buscarPorSub(String googleSub) {
        List<Usuario> encontrados = em.createQuery(
                        "select u from Usuario u where u.googleSub = :sub", Usuario.class)
                .setParameter("sub", googleSub)
                .setMaxResults(1)
                .getResultList();
        return encontrados.isEmpty() ? null : encontrados.get(0);
    }

    private Usuario actualizarEmail(Usuario usuario, String email) {
        if (email != null && !email.equals(usuario.getEmail())) {
            usuario.setEmail(email);
        }
        return usuario;
    }

    /**
     * Caso de uso de elección o cambio de namespace (issues #20 y #24).
     *
     * <p>La elección corre en su propia transacción con la fila del
     * usuario bloqueada y la unicidad final la impone la clave primaria
     * de {@code nombre_namespace}, compartida por nombres actuales y
     * anteriores. Todo nombre adoptado — en la primera elección o en un
     * cambio — queda registrado ahí con {@code grace_ends_at} nulo, y solo
     * recibe el fin de gracia cuando el usuario lo deja: la fila es lo
     * que serializa la carrera con quien intente tomarlo mientras tanto.
     * Al cambiar, los enlaces personales no se modifican.
     */
    public Usuario elegirNamespace(Long usuarioId, String propuesta) {
        String namespace = normalizarNamespace(propuesta);
        try {
            return transacciones.execute(status -> cambiarNamespace(usuarioId, namespace));
        } catch (DataIntegrityViolationException | PersistenceException carrera) {
            if (!esCarreraDeNombreNamespace(carrera)
                    && !esCarreraDeNamespace(carrera)) {
                throw carrera;
            }
            throw namespaceTomado(namespace);
        }
    }

    private Usuario cambiarNamespace(Long usuarioId, String namespace) {
        Usuario usuario = em.find(Usuario.class, usuarioId, LockModeType.PESSIMISTIC_WRITE);
        if (usuario == null) {
            throw new IllegalStateException(
                    "El usuario " + usuarioId + " de la sesión ya no existe");
        }
        validarFormatoNamespace(namespace);
        validarNamespaceReservado(namespace);
        NamespaceAnterior propio = anteriorEnGracia(usuario, namespace);
        if (namespace.equals(usuario.getNamespace())) {
            return usuario;
        }
        if (estaTomado(namespace, usuarioId)) {
            throw namespaceTomado(namespace);
        }
        boolean recuperarAnterior = propio != null;
        if (recuperarAnterior) {
            em.createQuery("delete from NamespaceAnterior n where n.namespace = :namespace")
                    .setParameter("namespace", namespace)
                    .executeUpdate();
            em.flush();
        } else {
            em.createQuery("delete from NamespaceAnterior n where n.namespace = :namespace "
                            + "and n.graceEndsAt <= :ahora")
                    .setParameter("namespace", namespace)
                    .setParameter("ahora", clock.instant())
                    .executeUpdate();
            em.flush();
            em.createQuery("delete from NombreNamespace n where n.namespace = :namespace "
                            + "and n.graceEndsAt <= :ahora")
                    .setParameter("namespace", namespace)
                    .setParameter("ahora", clock.instant())
                    .executeUpdate();
            em.flush();
        }
        String actual = usuario.getNamespace();
        if (actual != null) {
            Instant finGracia = clock.instant().plus(properties.namespaceGracePeriod());
            NombreNamespace nombreActual = em.find(NombreNamespace.class, actual);
            if (nombreActual == null) {
                em.persist(new NombreNamespace(actual, usuario, finGracia));
            } else {
                nombreActual.setGraceEndsAt(finGracia);
            }
            em.persist(new NamespaceAnterior(usuario, actual, finGracia));
        }
        // Todo nombre adoptado queda registrado con grace nulo: es lo que
        // estaTomado reconoce como actual, así que un cambio también lo
        // escribe — si no, el registro del nombre que se suelta no sería
        // visible para quien intente adoptarlo durante la gracia.
        NombreNamespace nombreNuevo = em.find(NombreNamespace.class, namespace);
        if (nombreNuevo == null) {
            em.persist(new NombreNamespace(namespace, usuario, null));
        } else {
            nombreNuevo.setGraceEndsAt(null);
        }
        usuario.setNamespace(namespace);
        em.flush();
        return usuario;
    }

    private NamespaceAnterior anteriorEnGracia(Usuario usuario, String namespace) {
        List<NamespaceAnterior> resultados = em.createQuery(
                        "select n from NamespaceAnterior n where n.usuario.id = :usuarioId "
                                + "and n.namespace = :namespace and n.graceEndsAt > :ahora",
                        NamespaceAnterior.class)
                .setParameter("usuarioId", usuario.getId())
                .setParameter("namespace", namespace)
                .setParameter("ahora", clock.instant())
                .setMaxResults(1)
                .getResultList();
        return resultados.isEmpty() ? null : resultados.get(0);
    }

    private boolean estaTomado(String namespace, Long usuarioId) {
        return !em.createQuery(
                        "select n.usuario.id from NombreNamespace n where n.namespace = :namespace "
                                + "and n.usuario.id <> :usuarioId "
                                + "and (n.graceEndsAt is null or n.graceEndsAt > :ahora)",
                        Long.class)
                .setParameter("namespace", namespace)
                .setParameter("ahora", clock.instant())
                .setParameter("usuarioId", usuarioId)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }

    @Transactional(readOnly = true)
    public List<NamespaceAnterior> namespacesAnteriores(Long usuarioId) {
        return em.createQuery("select n from NamespaceAnterior n where n.usuario.id = :usuarioId "
                        + "and n.graceEndsAt > :ahora order by n.graceEndsAt", NamespaceAnterior.class)
                .setParameter("usuarioId", usuarioId)
                .setParameter("ahora", clock.instant())
                .getResultList();
    }

    private static InvalidNamespaceException namespaceTomado(String namespace) {
        return new InvalidNamespaceException(Reason.NAMESPACE_TAKEN,
                "El namespace '" + namespace + "' ya está tomado; probá con otro.");
    }

    /** ¿La excepción es la violación única del namespace actual heredado? */
    private static boolean esCarreraDeNamespace(Throwable error) {
        for (Throwable actual = error; actual != null; actual = actual.getCause()) {
            if (actual instanceof ConstraintViolationException violacion
                    && "uq_usuario_namespace".equalsIgnoreCase(violacion.getConstraintName())) {
                return true;
            }
            String mensaje = actual.getMessage();
            // HSQLDB devuelve el nombre del constraint en mayúsculas.
            if (mensaje != null
                    && mensaje.toLowerCase(Locale.ROOT).contains("uq_usuario_namespace")) {
                return true;
            }
        }
        return false;
    }

    private static boolean esCarreraDeNombreNamespace(Throwable error) {
        for (Throwable actual = error; actual != null; actual = actual.getCause()) {
            if (actual instanceof ConstraintViolationException violacion
                    && ("nombre_namespace_pkey".equalsIgnoreCase(violacion.getConstraintName())
                            || "uq_namespace_anterior_namespace"
                                    .equalsIgnoreCase(violacion.getConstraintName()))) {
                return true;
            }
            String mensaje = actual.getMessage();
            if (mensaje != null && esViolacionUnicaDelRegistroDeNombres(mensaje)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Solo las violaciones de unicidad del registro de nombres son la
     * carrera que hay que traducir: una violación de clave foránea (p.
     * ej. {@code fk_namespace_anterior_usuario}) es un defecto de
     * integridad y no puede disfrazarse de {@code NAMESPACE_TAKEN}.
     * Postgres nombra la restricción ({@code nombre_namespace_pkey}) y
     * HSQLDB nombra la tabla cuando la clave primaria tiene nombre
     * generado, así que el mensaje se reconoce por la unicidad y la
     * tabla, no por la tabla sola.
     */
    private static boolean esViolacionUnicaDelRegistroDeNombres(String mensaje) {
        String minusculas = mensaje.toLowerCase(Locale.ROOT);
        boolean unica = minusculas.contains("unique")
                || minusculas.contains("duplicate");
        return unica && (minusculas.contains("nombre_namespace")
                || minusculas.contains("uq_namespace_anterior_namespace"));
    }

    private static String normalizarNamespace(String propuesta) {
        return propuesta == null ? null : propuesta.toLowerCase(Locale.ROOT);
    }

    private static void validarFormatoNamespace(String namespace) {
        if (namespace == null
                || namespace.length() < NAMESPACE_MIN
                || namespace.length() > NAMESPACE_MAX
                || !namespace.matches(NAMESPACE_VALIDO)) {
            throw new InvalidNamespaceException(Reason.NAMESPACE_INVALID,
                    "El namespace debe tener entre " + NAMESPACE_MIN + " y " + NAMESPACE_MAX
                            + " caracteres a-z, 0-9 y -, sin guion inicial ni final.");
        }
    }

    private void validarNamespaceReservado(String namespace) {
        if (properties.namespaceReservedNames().contains(namespace)) {
            throw new InvalidNamespaceException(Reason.NAMESPACE_RESERVED,
                    "El namespace '" + namespace + "' está reservado por el sistema;"
                            + " probá con otro.");
        }
    }

    /**
     * Sugerencia para el primer login (issue #20): la parte local del
     * email pasada al formato del namespace. No se reserva: si ya está
     * tomada, la web deja escribir otro nombre.
     */
    public String sugerenciaDeNamespace(String email) {
        if (email == null) {
            return SUGERENCIA_POR_DEFECTO;
        }
        int arroba = email.indexOf('@');
        String local = arroba < 0 ? email : email.substring(0, arroba);
        StringBuilder sugerencia = new StringBuilder();
        for (int i = 0; i < local.length(); i++) {
            char caracter = Character.toLowerCase(local.charAt(i));
            boolean valido = (caracter >= 'a' && caracter <= 'z')
                    || (caracter >= '0' && caracter <= '9');
            if (valido) {
                sugerencia.append(caracter);
            } else if (sugerencia.length() > 0
                    && sugerencia.charAt(sugerencia.length() - 1) != '-') {
                sugerencia.append('-');
            }
        }
        while (sugerencia.length() > 0
                && sugerencia.charAt(sugerencia.length() - 1) == '-') {
            sugerencia.setLength(sugerencia.length() - 1);
        }
        if (sugerencia.length() > NAMESPACE_MAX) {
            sugerencia.setLength(NAMESPACE_MAX);
            while (sugerencia.charAt(sugerencia.length() - 1) == '-') {
                sugerencia.setLength(sugerencia.length() - 1);
            }
        }
        return sugerencia.length() < NAMESPACE_MIN
                ? SUGERENCIA_POR_DEFECTO : sugerencia.toString();
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
