package io.github.spendice.linkshortener.service;

import io.github.spendice.linkshortener.config.CuentaProperties;
import io.github.spendice.linkshortener.config.ShortenerProperties;
import io.github.spendice.linkshortener.mappings.CodigoVinculacion;
import io.github.spendice.linkshortener.mappings.Usuario;
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

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
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
 * /api/sesion}; la vinculación por QR (issue #22) son
 * {@link #crearVinculacion()}, {@link #estadoDeVinculacion(String)},
 * {@link #aprobarVinculacion(String, Long)} y
 * {@link #consumirVinculacion(String)}.</p>
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
     * Atributo de sesión con el tipo de sesión: {@link #TIPO_PRINCIPAL}
     * del login directo o {@link #TIPO_VINCULADA} de la vinculación por
     * QR.
     */
    public static final String ATRIBUTO_SESION_TIPO = "sesionTipo";

    /** Tipo de la sesión abierta con login directo de Google. */
    public static final String TIPO_PRINCIPAL = "principal";

    /** Tipo de la sesión abierta por la vinculación por QR. */
    public static final String TIPO_VINCULADA = "vinculada";

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

    /** Entropía de los códigos de vinculación: 32 bytes por emisión. */
    private static final SecureRandom ALEATORIO = new SecureRandom();
    private static final int CODIGO_BYTES = 32;

    @Autowired
    EntityManager em;

    @Autowired
    TransactionTemplate transacciones;

    @Autowired
    CuentaProperties properties;

    @Autowired
    ShortenerProperties shortener;

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
     * Caso de uso de la elección de namespace en el primer login (issue
     * #20): valida formato y nombres reservados, comprueba que el nombre
     * esté libre sin distinguir mayúsculas y lo guarda normalizado.
     *
     * <p>La elección corre en su propia transacción con la fila del
     * usuario bloqueada (dos pedidos del mismo usuario no se pisan) y la
     * unicidad final la impone {@code uq_usuario_namespace}. Si dos
     * usuarios piden el mismo nombre a la vez, el perdedor recibe la
     * violación de la restricción y se traduce a {@code
     * NAMESPACE_TAKEN}: nunca terminan ambos con él. Un usuario que ya
     * tiene namespace solo puede repetir el mismo; cualquier otro nombre
     * se rechaza con {@code NAMESPACE_ALREADY_CHOSEN} porque el cambio
     * llega en otro Change.</p>
     */
    public Usuario elegirNamespace(Long usuarioId, String propuesta) {
        String namespace = normalizarNamespace(propuesta);
        try {
            return transacciones.execute(status -> asignarNamespace(usuarioId, namespace));
        } catch (DataIntegrityViolationException | PersistenceException carrera) {
            if (!esCarreraDeNamespace(carrera)) {
                throw carrera;
            }
            throw namespaceTomado(namespace);
        }
    }

    private Usuario asignarNamespace(Long usuarioId, String namespace) {
        Usuario usuario = em.find(Usuario.class, usuarioId, LockModeType.PESSIMISTIC_WRITE);
        if (usuario == null) {
            throw new IllegalStateException(
                    "El usuario " + usuarioId + " de la sesión ya no existe");
        }
        String actual = usuario.getNamespace();
        if (actual != null) {
            // El usuario ya eligió: repetir el mismo nombre es idempotente y
            // cualquier otro se rechaza antes de validar su formato, porque
            // por esta vía no hay cambio de namespace.
            if (actual.equalsIgnoreCase(namespace)) {
                return usuario;
            }
            throw new InvalidNamespaceException(Reason.NAMESPACE_ALREADY_CHOSEN,
                    "Ya elegiste el namespace '" + actual
                            + "'; cambiarlo llega en otro ticket.");
        }
        validarFormatoNamespace(namespace);
        validarNamespaceReservado(namespace);
        if (estaTomado(namespace)) {
            throw namespaceTomado(namespace);
        }
        usuario.setNamespace(namespace);
        // El flush dentro de la transacción hace saltar la restricción
        // única antes del commit, para poder traducir la carrera.
        em.flush();
        return usuario;
    }

    private boolean estaTomado(String namespace) {
        return !em.createQuery(
                        "select u.id from Usuario u where lower(u.namespace) = :namespace",
                        Long.class)
                .setParameter("namespace", namespace)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }

    private static InvalidNamespaceException namespaceTomado(String namespace) {
        return new InvalidNamespaceException(Reason.NAMESPACE_TAKEN,
                "El namespace '" + namespace + "' ya está tomado; probá con otro.");
    }

    /** ¿La excepción es la violación única de {@code uq_usuario_namespace}? */
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

    // ---------------------------------------------------------------
    // Vinculación por QR (issue #22, ADR 0007): el dispositivo que espera
    // pide un código de un solo uso que vence a los `link-code-ttl`, lo
    // muestra como QR con la URL de confirmación y consulta su estado;
    // una sesión principal lo aprueba y la consulta siguiente lo consume
    // y abre la sesión vinculada.
    // ---------------------------------------------------------------

    /**
     * Emite un código de vinculación pendiente: 256 bits aleatorios en
     * base64url (inadivinable) y la URL de confirmación compuesta sobre
     * {@code public-base-url} con {@code /vincular/{codigo}}, que es lo
     * que codifica el QR. El vencimiento es el {@code link-code-ttl}
     * configurado desde la creación.
     */
    public CodigoVinculacionCreado crearVinculacion() {
        byte[] aleatorio = new byte[CODIGO_BYTES];
        ALEATORIO.nextBytes(aleatorio);
        String codigo = Base64.getUrlEncoder().withoutPadding().encodeToString(aleatorio);
        Instant creadaEn = clock.instant();
        Instant venceEn = creadaEn.plus(properties.linkCodeTtl());
        transacciones.executeWithoutResult(status -> {
            em.persist(new CodigoVinculacion(codigo, creadaEn, venceEn));
            em.flush();
        });
        return new CodigoVinculacionCreado(codigo, direccionBase() + "/vincular/" + codigo,
                venceEn);
    }

    /**
     * Estado efectivo del código para el dispositivo que aprueba: lee
     * sin bloquear y nunca consume. Los estados {@code VENCIDA} e
     * {@code INEXISTENTE} son el rechazo que la página muestra como
     * «pedí uno nuevo».
     */
    @Transactional(readOnly = true)
    public EstadoVinculacion estadoDeVinculacion(String codigo) {
        CodigoVinculacion vinculacion = em.find(CodigoVinculacion.class, codigo);
        if (vinculacion == null) {
            return EstadoVinculacion.INEXISTENTE;
        }
        return estadoEfectivo(vinculacion, clock.instant());
    }

    /**
     * Aprobación desde la sesión principal del usuario: solo un código
     * pendiente y vigente queda aprobado; cualquier otro estado (vencido,
     * ya aprobado, ya consumido o inexistente) devuelve {@code false} y
     * no abre nada. El bloqueo pesimista de la fila serializa dos
     * aprobaciones simultáneas y la carrera con la consulta que consume.
     */
    public boolean aprobarVinculacion(String codigo, Long usuarioId) {
        return Boolean.TRUE.equals(transacciones.execute(status -> {
            CodigoVinculacion vinculacion =
                    em.find(CodigoVinculacion.class, codigo, LockModeType.PESSIMISTIC_WRITE);
            if (vinculacion == null
                    || estadoEfectivo(vinculacion, clock.instant())
                            != EstadoVinculacion.PENDIENTE) {
                return false;
            }
            vinculacion.setEstado(CodigoVinculacion.Estado.APROBADA);
            vinculacion.setUsuarioId(usuarioId);
            return true;
        }));
    }

    /**
     * Consulta del dispositivo que espera: responde el estado efectivo y,
     * cuando es {@code APROBADA}, consume el código (de un solo uso) y
     * devuelve el usuario que aprobó para materializar la sesión
     * vinculada. La siguiente consulta con el mismo código responde
     * {@code VENCIDA} y no abre sesión. El bloqueo pesimista garantiza
     * que una sola consulta consuma la aprobación.
     */
    public ResultadoVinculacion consumirVinculacion(String codigo) {
        return transacciones.execute(status -> {
            CodigoVinculacion vinculacion =
                    em.find(CodigoVinculacion.class, codigo, LockModeType.PESSIMISTIC_WRITE);
            if (vinculacion == null) {
                return new ResultadoVinculacion(EstadoVinculacion.INEXISTENTE, null);
            }
            EstadoVinculacion estado = estadoEfectivo(vinculacion, clock.instant());
            if (estado != EstadoVinculacion.APROBADA) {
                return new ResultadoVinculacion(estado, null);
            }
            vinculacion.setEstado(CodigoVinculacion.Estado.CONSUMIDA);
            Usuario aprobador = em.find(Usuario.class, vinculacion.getUsuarioId());
            return new ResultadoVinculacion(EstadoVinculacion.APROBADA, aprobador);
        });
    }

    /**
     * Estado efectivo: el vencimiento se evalúa en el instante del
     * servidor, como las asignaciones. Un código usado equivale a uno
     * vencido para el que espera: tampoco abre sesión.
     */
    private EstadoVinculacion estadoEfectivo(CodigoVinculacion vinculacion, Instant ahora) {
        if (!vinculacion.getVenceEn().isAfter(ahora)) {
            return EstadoVinculacion.VENCIDA;
        }
        return switch (vinculacion.getEstado()) {
            case PENDIENTE -> EstadoVinculacion.PENDIENTE;
            case APROBADA -> EstadoVinculacion.APROBADA;
            case CONSUMIDA -> EstadoVinculacion.VENCIDA;
        };
    }

    /** Dirección pública del servicio sin barra final, como en AcortadorService. */
    private String direccionBase() {
        String base = shortener.publicBaseUrl().toString();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
