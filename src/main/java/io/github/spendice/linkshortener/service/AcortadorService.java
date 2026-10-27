package io.github.spendice.linkshortener.service;

import io.github.spendice.linkshortener.config.ShortenerProperties;
import io.github.spendice.linkshortener.mappings.Alias;
import io.github.spendice.linkshortener.mappings.Asignacion;
import io.github.spendice.linkshortener.mappings.AsignacionPersonal;
import io.github.spendice.linkshortener.mappings.GeneradorAlias;
import io.github.spendice.linkshortener.mappings.Usuario;
import io.github.spendice.linkshortener.service.InvalidDestinationException.Reason;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Servicio único del acortador: concentra los casos de uso como métodos
 * públicos — creación y resolución de enlaces acortados ({@link #acortar}
 * y {@link #resolver}) y de enlaces personales
 * ({@link #crearEnlacePersonal} y {@link #resolverEnlacePersonal},
 * issue #21) — y todas las reglas de negocio como helpers privados
 * del mismo archivo (esquema de clase). Persiste con {@code EntityManager}
 * y HQL directo sobre los mappings.
 *
 * <p>Reserva de alias, creación de la asignación y actualización de la
 * referencia actual forman una unidad atómica en {@link #acortar}
 * (ADR 0002); las asignaciones persistidas no se modifican ni se
 * eliminan: reutilizar un alias agrega una nueva asignación y mueve la
 * referencia actual (ADR 0003). Las asignaciones personales siguen la
 * misma política de historial, serializadas por la fila del usuario.</p>
 */
@Service
public class AcortadorService {

    private static final String AVISO_REUSO =
            "Este alias puede reutilizarse con otro destino después del vencimiento.";

    /**
     * Alias personalizado válido (issue #21): de 1 a 50 caracteres
     * minúsculas, dígitos y guiones, sin restricción de posición del
     * guion. La entrada se normaliza a minúsculas antes de validar, así
     * que «Mi-Doc» es un alias válido que se guarda como «mi-doc».
     */
    /** Lo que entra: sólo ASCII. `toLowerCase` mapea Unicode a ASCII (p. ej. KELVIN SIGN → k). */
    private static final String ALIAS_PERSONALIZADO_ENTRADA = "[A-Za-z0-9-]{1,50}";
    private static final int ALIAS_PERSONALIZADO_MAX = 50;

    @Autowired
    EntityManager em;

    @Autowired
    ShortenerProperties properties;

    @Autowired
    Clock clock;

    private String direccionBase;
    private Set<String> origenesPropios;
    private Set<String> rutasReservadas;

    @PostConstruct
    void inicializar() {
        String base = properties.publicBaseUrl().toString();
        this.direccionBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.origenesPropios = calcularOrigenesPropios();
        this.rutasReservadas = Set.copyOf(properties.reservedRoutes());
    }

    /**
     * Caso de uso de creación: valida el destino, reserva un alias (uno
     * vencido reutilizable o un código nuevo) y persiste una asignación
     * independiente con su vencimiento, todo en una única transacción.
     */
    @Transactional
    public EnlaceCreado acortar(String destino) {
        validarDestino(destino);
        Instant ahora = clock.instant();
        Alias alias = reservarAlias(ahora);
        Asignacion asignacion = new Asignacion(alias.getCodigo(), destino, ahora, venceEn(ahora));
        em.persist(asignacion);
        alias.setAsignacionActualId(asignacion.getId());
        return new EnlaceCreado(
                direccionBase + "/" + alias.getCodigo(),
                alias.getCodigo(),
                asignacion.getVenceEn(),
                AVISO_REUSO);
    }

    /**
     * Caso de uso de resolución: devuelve la asignación vigente del
     * alias en el instante del servidor, o vacío si el alias es
     * desconocido o su asignación ya venció. Lee destino y vencimiento
     * de una única asignación (la actual del alias), por lo que una
     * reasignación concurrente no puede mezclar datos de asignaciones
     * distintas.
     */
    @Transactional(readOnly = true)
    public Optional<Asignacion> resolver(String alias) {
        if (!esCodigo(alias)) {
            return Optional.empty();
        }
        Alias encontrado = em.find(Alias.class, alias);
        if (encontrado == null || encontrado.getAsignacionActualId() == null) {
            return Optional.empty();
        }
        Asignacion asignacion = em.find(Asignacion.class, encontrado.getAsignacionActualId());
        if (asignacion == null || !clock.instant().isBefore(asignacion.getVenceEn())) {
            return Optional.empty();
        }
        return Optional.of(asignacion);
    }

    /**
     * Caso de uso de creación de un enlace personal (issue #21): valida
     * el destino con las mismas reglas del enlace acortado, exige que el
     * usuario ya haya elegido su namespace y persiste una asignación que
     * pertenece al usuario (no al texto del namespace), conservando el
     * historial (ADR 0003).
     *
     * <p>El bloqueo pesimista de la fila del usuario serializa las
     * creaciones del mismo usuario, la misma disciplina que la fila del
     * generador para los alias de un segmento: dos creaciones
     * simultáneas del mismo alias personalizado nunca terminan con dos
     * asignaciones vigentes — la que espera ve la vigente que confirmó
     * la otra y recibe {@code ALIAS_TAKEN}.</p>
     */
    @Transactional
    public EnlaceCreado crearEnlacePersonal(Long usuarioId, String propuestaAlias,
                                            String destino) {
        Usuario usuario = em.find(Usuario.class, usuarioId, LockModeType.PESSIMISTIC_WRITE);
        if (usuario == null) {
            throw new IllegalStateException(
                    "El usuario " + usuarioId + " de la sesión ya no existe");
        }
        String namespace = usuario.getNamespace();
        if (namespace == null) {
            throw new InvalidNamespaceException(
                    InvalidNamespaceException.Reason.NAMESPACE_NOT_CHOSEN,
                    "Elegí tu namespace para crear enlaces personales.");
        }
        validarDestino(destino);
        String alias = normalizarAliasPersonalizado(propuestaAlias);
        Instant ahora = clock.instant();
        if (existeAsignacionPersonalVigente(usuario.getId(), alias, ahora)) {
            throw new InvalidAliasPersonalizadoException(
                    InvalidAliasPersonalizadoException.Reason.ALIAS_TAKEN,
                    "Ya tenés un enlace personal vigente con el alias '" + alias
                            + "'; probá con otro.");
        }
        AsignacionPersonal asignacion = new AsignacionPersonal(
                usuario.getId(), alias, destino, ahora, venceEn(ahora));
        em.persist(asignacion);
        return new EnlaceCreado(
                direccionBase + "/" + namespace + "/" + alias,
                alias,
                asignacion.getVenceEn(),
                AVISO_REUSO);
    }

    /**
     * Caso de uso de resolución de un enlace personal (issue #21): el
     * namespace ubica al usuario dueño y devuelve su asignación vigente
     * de ese alias personalizado en el instante del servidor, o vacío si
     * no hay usuario con ese namespace, si el enlace no existe o si ya
     * venció. La comparación no distingue mayúsculas: ambos segmentos se
     * normalizan a minúsculas. Al ser una asignación por usuario, otro
     * usuario puede tener el mismo texto de alias en su namespace.
     */
    @Transactional(readOnly = true)
    public Optional<AsignacionPersonal> resolverEnlacePersonal(String namespace,
                                                             String propuestaAlias) {
        List<Usuario> usuarios = em.createQuery(
                "select u from Usuario u where u.namespace = :namespace", Usuario.class)
                .setParameter("namespace", namespace.toLowerCase(Locale.ROOT))
                .setMaxResults(1)
                .getResultList();
        if (usuarios.isEmpty()) {
            return Optional.empty();
        }
        return em.createQuery(
                "select a from AsignacionPersonal a"
                        + " where a.usuarioId = :usuario"
                        + " and a.aliasPersonalizado = :alias"
                        + " and a.venceEn > :instante"
                        + " order by a.id desc",
                AsignacionPersonal.class)
                .setParameter("usuario", usuarios.get(0).getId())
                .setParameter("alias", propuestaAlias.toLowerCase(Locale.ROOT))
                .setParameter("instante", clock.instant())
                .setMaxResults(1)
                .getResultList()
                .stream().findFirst();
    }

    /** El alias personalizado normalizado y verificado contra el formato. */
    private String normalizarAliasPersonalizado(String propuesta) {
        // Se valida el original, no su minúscula: `toLowerCase` convierte
        // caracteres Unicode en ASCII (KELVIN SIGN pasa a `k`) y los aceptaría.
        if (propuesta == null || !propuesta.matches(ALIAS_PERSONALIZADO_ENTRADA)) {
            throw new InvalidAliasPersonalizadoException(
                    InvalidAliasPersonalizadoException.Reason.ALIAS_INVALID,
                    "El alias personalizado debe tener entre 1 y " + ALIAS_PERSONALIZADO_MAX
                            + " caracteres a-z, 0-9 y -.");
        }
        return propuesta.toLowerCase(Locale.ROOT);
    }

    /** ¿El usuario ya tiene una asignación vigente de ese alias personalizado? */
    private boolean existeAsignacionPersonalVigente(Long usuarioId, String alias,
                                                    Instant instante) {
        return !em.createQuery(
                "select a.id from AsignacionPersonal a"
                        + " where a.usuarioId = :usuario"
                        + " and a.aliasPersonalizado = :alias"
                        + " and a.venceEn > :instante",
                Long.class)
                .setParameter("usuario", usuarioId)
                .setParameter("alias", alias)
                .setParameter("instante", instante)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }

    // ---- Reserva de alias (reciclaje y generación) ----

    /**
     * Reserva atómicamente un alias para una asignación creada en
     * {@code ahora}: prefiere un alias cuya asignación actual está
     * vencida en ese instante y, solo si no hay ninguno reutilizable,
     * emite el próximo código nunca usado ni reservado. El bloqueo
     * pesimista de la fila del generador serializa toda la selección:
     * dos creaciones concurrentes no pueden reciclar el mismo alias ni
     * saltarse un vencido más corto disponible.
     */
    private Alias reservarAlias(Instant ahora) {
        GeneradorAlias generador = em.find(GeneradorAlias.class, GeneradorAlias.SINGLETON_ID,
                LockModeType.PESSIMISTIC_WRITE);
        if (generador == null) {
            throw new IllegalStateException("Falta la fila del generador de alias");
        }
        List<String> reciclables = em.createQuery(
                "select al.codigo from Alias al, Asignacion a"
                        + " where a.aliasCodigo = al.codigo"
                        + " and a.id = al.asignacionActualId"
                        + " and a.venceEn <= :instante",
                String.class)
                .setParameter("instante", ahora)
                .getResultList();
        Optional<String> reciclado = elegirReciclable(reciclables);
        if (reciclado.isPresent()) {
            // El alias ya existe: conserva su historial y solo cambia su
            // referencia actual, sin consumir un código del generador.
            Alias alias = em.find(Alias.class, reciclado.get());
            if (alias == null) {
                throw new IllegalStateException("Alias inexistente: " + reciclado.get());
            }
            return alias;
        }
        long indice = indiceDelSiguienteCodigo(generador.getProximoIndice());
        generador.setProximoIndice(indice + 1);
        Alias alias = new Alias(codigoEn(indice));
        // La asignación inserta de inmediato (IDENTITY) y referencia
        // alias.codigo: la fila del alias tiene que existir antes.
        em.persist(alias);
        em.flush();
        return alias;
    }

    /**
     * Prioridad de reciclaje: menor longitud primero; a igualdad de
     * longitud desempata el orden natural (lexicográfico) del código,
     * que distingue mayúsculas de minúsculas y es determinista (ADR 0002).
     */
    private Optional<String> elegirReciclable(List<String> vencidos) {
        return vencidos.stream()
                .min(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
    }

    /**
     * Primer índice, a partir de {@code desde}, cuyo código no está
     * reservado (las rutas reservadas de la aplicación se saltan sin
     * emitirse).
     */
    private long indiceDelSiguienteCodigo(long desde) {
        long indice = desde;
        while (rutasReservadas.contains(codigoEn(indice))) {
            indice++;
        }
        return indice;
    }

    /**
     * Código del alfabeto que ocupa la posición {@code indice} en la
     * enumeración: posiciones 0..57 son códigos de un carácter,
     * 58..3421 de dos, y así sucesivamente. Dentro de cada longitud el
     * orden es el del alfabeto, por lo que el desempate es determinista.
     */
    private String codigoEn(long indice) {
        if (indice < 0) {
            throw new IllegalArgumentException("El índice de código no puede ser negativo: " + indice);
        }
        String alfabeto = properties.aliasAlphabet();
        long offset = indice;
        int longitud = 1;
        long tramo = alfabeto.length();
        while (offset >= tramo) {
            offset -= tramo;
            longitud++;
            tramo *= alfabeto.length();
        }
        char[] codigo = new char[longitud];
        for (int i = longitud - 1; i >= 0; i--) {
            codigo[i] = alfabeto.charAt((int) (offset % alfabeto.length()));
            offset /= alfabeto.length();
        }
        return new String(codigo);
    }

    /** El valor solo contiene símbolos del alfabeto configurado. */
    private boolean esCodigo(String valor) {
        String alfabeto = properties.aliasAlphabet();
        return valor != null
                && !valor.isEmpty()
                && valor.chars().allMatch(simbolo -> alfabeto.indexOf(simbolo) >= 0);
    }

    // ---- Reglas temporales y de destino ----

    /**
     * El vencimiento se calcula una sola vez, al crear, como
     * {@code creadaEn + link-duration}: las asignaciones persistidas
     * conservan su propio {@code vence_en}.
     */
    private Instant venceEn(Instant creadaEn) {
        return creadaEn.plus(properties.linkDuration());
    }

    /**
     * Reglas de aceptación de la URL de destino: URI absoluta http/https
     * de hasta {@code max-destination-length} caracteres, sin espacios ni
     * caracteres internacionales sin codificar, y fuera de los orígenes
     * propios del servicio. No consulta la disponibilidad del destino ni
     * modifica la entrada: el destino aceptado se persiste verbatim.
     */
    private void validarDestino(String destino) {
        if (destino == null || destino.isBlank()) {
            throw new InvalidDestinationException(Reason.EMPTY_DESTINATION,
                    "La URL de destino es obligatoria.");
        }
        if (destino.length() > properties.maxDestinationLength()) {
            throw new InvalidDestinationException(Reason.DESTINATION_TOO_LONG,
                    "La URL de destino supera el máximo de "
                            + properties.maxDestinationLength() + " caracteres.");
        }
        if (!destino.chars().allMatch(c -> c <= 0x7F)) {
            throw new InvalidDestinationException(Reason.MALFORMED_DESTINATION,
                    "Los caracteres internacionales deben ir codificados"
                            + " (percent-encoding o dominio punycode).");
        }
        URI uri;
        try {
            uri = new URI(destino);
        } catch (URISyntaxException e) {
            throw new InvalidDestinationException(Reason.MALFORMED_DESTINATION,
                    "La URL de destino no tiene un formato válido: " + e.getReason());
        }
        String esquema = uri.getScheme();
        if (esquema == null || !(esquema.equalsIgnoreCase("http") || esquema.equalsIgnoreCase("https"))) {
            throw new InvalidDestinationException(Reason.UNSUPPORTED_SCHEME,
                    "La URL de destino debe usar esquema http o https.");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new InvalidDestinationException(Reason.MALFORMED_DESTINATION,
                    "La URL de destino no indica un host válido.");
        }
        int puerto = uri.getPort() >= 0 ? uri.getPort() : puertoPorDefecto(esquema);
        if (esOrigenPropio(uri.getHost(), puerto)) {
            throw new InvalidDestinationException(Reason.OWN_ORIGIN,
                    "La URL de destino pertenece al propio acortador.");
        }
    }

    /**
     * El origen (host normalizado + puerto efectivo) ya está entre los
     * orígenes propios. El esquema no distingue orígenes: un destino al
     * mismo host y puerto pertenece al acortador independientemente del
     * esquema declarado.
     */
    private boolean esOrigenPropio(String host, int puerto) {
        return origenesPropios.contains(claveOrigen(host, puerto));
    }

    /**
     * Orígenes propios como claves {@code host:puerto} normalizadas: la
     * dirección pública configurada, los orígenes declarados y los
     * equivalentes loopback del puerto público ({@code localhost},
     * {@code 127.0.0.1} y {@code ::1}), que en la demo LAN apuntan al
     * mismo servicio.
     */
    private Set<String> calcularOrigenesPropios() {
        Set<String> origenes = new LinkedHashSet<>();
        URI publica = properties.publicBaseUrl();
        int puertoPublico = publica.getPort() >= 0
                ? publica.getPort() : puertoPorDefecto(publica.getScheme());
        origenes.add(claveOrigen(publica.getHost(), puertoPublico));
        for (String loopback : List.of("localhost", "127.0.0.1", "::1")) {
            origenes.add(claveOrigen(loopback, puertoPublico));
        }
        for (String propio : properties.ownOrigins()) {
            if (propio != null && !propio.isBlank()) {
                URI uri = URI.create(propio.trim());
                if (uri.getHost() == null) {
                    throw new IllegalArgumentException("Origen sin host: " + uri);
                }
                origenes.add(claveOrigen(uri.getHost(),
                        uri.getPort() >= 0 ? uri.getPort() : puertoPorDefecto(uri.getScheme())));
            }
        }
        return origenes;
    }

    private static String claveOrigen(String host, int puerto) {
        return normalizarHost(host) + ":" + puerto;
    }

    private static int puertoPorDefecto(String esquema) {
        return "https".equalsIgnoreCase(esquema) ? 443 : 80;
    }

    /**
     * Minúsculas, sin los corchetes de la forma literal IPv6 y sin el
     * punto final del nombre de dominio absoluto.
     */
    private static String normalizarHost(String host) {
        String normalizado = host.toLowerCase(Locale.ROOT);
        if (normalizado.startsWith("[") && normalizado.endsWith("]")) {
            normalizado = normalizado.substring(1, normalizado.length() - 1);
        }
        if (normalizado.endsWith(".")) {
            normalizado = normalizado.substring(0, normalizado.length() - 1);
        }
        return normalizado;
    }
}
