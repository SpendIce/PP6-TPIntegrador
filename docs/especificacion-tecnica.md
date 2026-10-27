# Especificación técnica: creación y resolución de enlaces

Estado: decisiones técnicas fijadas para el issue #2, extendidas con la
evidencia de vencimiento y reinicio del issue #3, la reutilización de
alias del issue #5, las garantías de concurrencia del issue #6, la capa
de seguridad con CSRF del issue #18, el inicio de sesión con Google con
sesión principal persistida del issue #19, la elección del namespace del
primer login del issue #20 y los enlaces personales con alias
personalizado del issue #21 y el cambio de namespace con período de gracia
del issue #24. La
estructura descrita refleja el refactor al esquema de clase del issue
#15 (service único con `EntityManager` y HQL sobre las entities de
`mappings`, [ADR 0006](adr/0006-service-unico-hql-mappings.md)). Cierra
los puntos que
[arquitectura y validación](arquitectura-y-validacion.md) dejó pendientes de
especificación técnica: versiones concretas, ejecución de PostgreSQL, mecanismo
de migraciones, coordinación transaccional, reservas de rutas, desempate de
alias y configuración de orígenes propios. No modifica los acuerdos de
producto de [requisitos de etapa 1](requisitos-etapa-1.md).

## Contrato

El contrato REST de creación y resolución está en [openapi.yaml](../openapi.yaml):

- `POST /api/links` recibe `{"destination": "<url>"}` con el
  `aliasPersonalizado` opcional del issue #21 y devuelve `201` con
  `shortUrl`, `alias`, `expiresAt` y `reuseNotice`. Sin
  `aliasPersonalizado` (ausente, `null` o vacío, también con sesión) la
  creación es la de la etapa 1. Con `aliasPersonalizado` crea el enlace
  personal `/{namespace}/{alias personalizado}` del usuario de la sesión:
  de 1 a 50 caracteres `a-z`, `0-9` y `-`, sin distinguir mayúsculas (se
  normaliza y guarda en minúsculas). El formato inválido responde `400`
  con `ALIAS_INVALID`; sin sesión responde `401` con `NOT_AUTHENTICATED`;
  con sesión y sin namespace elegido responde `409` con
  `NAMESPACE_NOT_CHOSEN`; y repetir un alias personalizado vigente del
  mismo usuario responde `409` con `ALIAS_TAKEN` (al vencer puede
  reusarlo, y otro usuario siempre puede usar el mismo texto en su propio
  namespace). Los rechazos de entrada son `400` con `{error, message}`;
  los códigos de `error` posibles figuran en el contrato
  (`EMPTY_DESTINATION`, `DESTINATION_TOO_LONG`, `MALFORMED_DESTINATION`,
  `UNSUPPORTED_SCHEME`, `OWN_ORIGIN`, `INVALID_REQUEST`). Un token CSRF
  ausente o inválido se rechaza con `403` y el mismo formato
  (`CSRF_TOKEN_INVALID`); ver «Seguridad y CSRF».
- `GET /api/csrf` emite el token CSRF: fija la cookie legible `XSRF-TOKEN` y
  devuelve el mismo valor en `{"token": "..."}` para los clientes que no pueden
  leer `Set-Cookie` (las extensiones). Responde `Cache-Control: no-store`.
- `GET /{alias}` devuelve `302` con `Location` al destino conservado y
  `Cache-Control: no-store` cuando hay asignación vigente; `404` con la página
  «Este enlace no existe o venció» y `Cache-Control: no-store` en caso contrario.
- `GET /{namespace}/{aliasPersonalizado}` (issue #21) resuelve el enlace
  personal del usuario dueño del namespace: `302` con `Location` al
  destino y `Cache-Control: no-store` mientras la asignación está
  vigente, y `404` con la misma página y `no-store` si el namespace no
  existe, el enlace no existe o ya venció. Ambos segmentos se comparan
  sin distinguir mayúsculas (el alias se guarda en minúsculas y el
  namespace ya nace normalizado). La ruta de un segmento no cambia:
  `/juan` sigue resolviendo el alias `juan` de la etapa 1 aunque el
  texto coincida con el namespace de un usuario, nunca un perfil.
- `GET /api/sesion` devuelve `{"usuario": null}` sin sesión o sin usuario
  en la sesión, y `{"usuario": {"email", "namespace", "tipo", "sugerencia"}}`
  con sesión iniciada: `email` de la cuenta de Google, `namespace` vacío
  hasta que el usuario lo elija, `tipo` `principal` (la vinculación por QR
  sumará `vinculada`) y `sugerencia` para la pantalla del primer login
  (vacía cuando ya hay namespace). Responde `Cache-Control: no-store`.
- `POST /api/namespace` elige o cambia el namespace del usuario de la sesión.
  Normaliza a minúsculas y exige de 3 a 30 caracteres `a-z`, `0-9` y `-`,
  sin guion inicial ni final. Rechaza con su propio código en
  `{error, message}`: `NAMESPACE_INVALID` (`400`), `NAMESPACE_RESERVED`
  (`400`) y `NAMESPACE_TAKEN` (`409`);
  sin sesión responde `401` con `NOT_AUTHENTICATED` y exige CSRF como todo
  POST. Devuelve la sesión actual ya actualizada. Al cambiar, guarda el
  namespace anterior con su fin de gracia calculado una sola vez.
- `GET /api/namespace/anteriores` lista los namespaces anteriores del usuario
  cuya gracia sigue vigente con `namespace` y `graceEndsAt` UTC; requiere
  sesión y responde `Cache-Control: no-store`.
- `GET /api/namespace/sugerencia` propone un nombre derivado de la parte
  local del email, adaptado al formato. No lo reserva: si está tomado, el
  usuario puede escribir otro. Sin sesión responde `401`.
- `POST /api/logout` invalida la sesión del dispositivo que llama y
  responde `204` sin cuerpo, también cuando no hay sesión. Exige el token
  CSRF del doble envío, como todo POST.
- El inicio de sesión no pasa por la API: la web navega a
  `GET /oauth2/authorization/google` y el proveedor OIDC vuelve por
  `GET /login/oauth2/code/google`, los caminos por defecto del cliente
  OAuth2 de Spring Security. La vuelta deja una sesión principal
  persistida y redirige a la web (`/`); ver «Sesiones y login con
  Google».

Casos de entrada fijados por el contrato (los ejemplos por código figuran en
la respuesta `400` de `openapi.yaml`):

- El destino se valida como URI absoluta (RFC 3986) con esquema `http` o
  `https` y host presente; el límite es 8.192 caracteres sin truncamiento.
- Caracteres internacionales: una URI cruda con caracteres no ASCII se
  rechaza como `MALFORMED_DESTINATION`. Las formas aceptadas son dominio
  punycode (`xn--…`) y percent-encoding UTF-8 (`%C3%A9`); ambas se conservan
  verbatim.
- Percent-encoding inválido (`%` sin dos dígitos hexadecimales, p. ej.
  `%zz`, `%2` o `%` suelto) se rechaza como `MALFORMED_DESTINATION`; los
  escapes válidos se conservan sin decodificar ni normalizar (mayúsculas y
  minúsculas incluidas).
- Los espacios deben ir codificados (`%20`); un espacio en crudo en
  cualquier componente (incluido el fragmento) es `MALFORMED_DESTINATION`.
- Se conservan parámetros, fragmento, credenciales, puerto explícito y
  codificación verbatim. No se consulta la disponibilidad del destino.
- Orígenes propios: la comparación usa host normalizado (minúsculas, sin
  punto final ni corchetes IPv6) y puerto efectivo (explícito o 80/443
  según el esquema), ignorando el esquema. Coinciden el origen de
  `public-base-url`, los equivalentes loopback del mismo puerto y los
  orígenes declarados en `own-origins`; otro puerto u otro host LAN no es
  origen propio.
- El alias personalizado se valida antes de persistir: vacío, ausente o
  `null` no es un formato inválido, pide el enlace acortado de siempre;
  cualquier otro valor se normaliza a minúsculas y exige de 1 a 50
  caracteres `a-z`, `0-9` y `-`.
- Ningún rechazo crea asignación ni consume un alias: la validación ocurre
  antes de la reserva dentro de la transacción de `acortar`, y la de los
  enlaces personales antes de persistir dentro de `crearEnlacePersonal`.

## Seguridad y CSRF

La capa de seguridad (issue #18, en `config/SecurityConfig`; el issue #19
suma el login con Google y las sesiones persistidas) mantiene el flujo
anónimo de la etapa 1:

- Todo el contrato sigue sin autenticación salvo la creación de enlaces
  personales: `GET /{alias}`, `GET /{namespace}/{aliasPersonalizado}`,
  la página 404, la web, los estáticos y las rutas reservadas no piden
  credenciales, y crear enlaces acortados no exige sesión. `POST
  /api/links` con `aliasPersonalizado` sí exige la sesión principal del
  usuario y sin ella responde `401` con `NOT_AUTHENTICATED`.
- Se usa el firewall por defecto de Spring Security (`DefaultHttpFirewall`), no
  el estricto: el estricto rechaza con `400` el `;` en la ruta y el doble
  slash, y la etapa 1 resolvía `/abc;utm=x` y `//abc` como `/abc`. Conservar
  esa respuesta es parte del criterio; el ruteo sigue acotado por el patrón de
  alias y las rutas reservadas.
- Se apaga el escritor de `Cache-Control` por defecto de Spring Security
  (`headers().cacheControl().disable()`): sin eso, los estáticos quedaban con
  `no-store`. La resolución, la API y el token siguen declarando `no-store`
  desde sus controladores.
- Quedan los encabezados de seguridad por defecto de Spring Security en todas
  las respuestas: `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: DENY` y `X-XSS-Protection: 0`.
- Las sesiones se crean solo cuando hace falta (`IF_REQUIRED`, el default
  sin política explícita): el flujo anónimo no emite cookie de sesión y
  ninguna respuesta anónima crea una. La sesión nace al iniciar el login
  con Google (guarda el estado del pedido OAuth2) y se materializa como
  sesión principal al volver; ver «Sesiones y login con Google».
- Los métodos que cambian estado exigen CSRF por doble envío con
  `CookieCsrfTokenRepository.withHttpOnlyFalse()`: el servidor fija la cookie
  legible `XSRF-TOKEN` y el cliente la repite en el encabezado `X-XSRF-TOKEN`.
  La comparación es con el valor crudo de la cookie
  (`CsrfTokenRequestAttributeHandler`), la forma que espera un cliente que lee
  la cookie.
- `GET /api/csrf` resuelve el `CsrfToken` del filtro — lo que escribe la
  cookie — y devuelve el mismo valor en el cuerpo `{"token": "..."}`: las
  extensiones no pueden leer `Set-Cookie` desde `fetch`, así que toman el
  valor del cuerpo y piden el reenvío de la cookie con
  `credentials: "include"`. La web pide el token en el mismo origen antes de
  cada `POST /api/links`.
- El rechazo CSRF responde `403` con el formato `{error, message}` del
  contrato y el código `CSRF_TOKEN_INVALID`, escrito por un
  `DelegatingAccessDeniedHandler` que solo aplica ese cuerpo a
  `CsrfException`: los `403` de autorización que agregue el login (#19) no
  heredan el código CSRF.
- El doble envío no firma ni guarda tokens: cualquier par cookie/encabezado
  coincidente se acepta, que es lo que hace segura la comparación sin estado
  (un sitio cross-site no puede leer la cookie para repetirla). El mismo
  mecanismo protege `POST /api/logout`.

## Sesiones y login con Google

El inicio de sesión (issue #19, [ADR 0007](adr/0007-login-google-y-sesiones-persistidas.md))
usa el cliente OIDC de Spring Security con Google como único proveedor:

- El registro `google` pide exactamente los alcances `openid email profile`.
  Las credenciales llegan por `GOOGLE_CLIENT_ID` y `GOOGLE_CLIENT_SECRET`
  del entorno o del `.env`, sin default: `application.yml` solo tiene el
  placeholder y un chequeo de arranque corta el servicio nombrando la
  variable que falta (el binding deja el placeholder sin resolver como
  literal, así que también se rechaza el valor `${...}`).
- La vuelta del proveedor es `${PUBLIC_BASE_URL}/login/oauth2/code/google`,
  el camino por defecto de Spring Security, registrado en Google Cloud
  (ver `docs/despliegue-funnel-google.md`). En producción los endpoints se
  resuelven desde `accounts.google.com` por el proveedor conocido de
  Spring Security; en pruebas se configura el `issuer-uri` del proveedor
  simulado.
- El `redirect_uri` se arma con el esquema y el host de la solicitud, y
  `server.forward-headers-strategy: framework` honra los encabezados
  `X-Forwarded-Proto`/`X-Forwarded-Host`/`X-Forwarded-Port` que agrega
  Tailscale Funnel. Sin eso la vuelta saldría `http://` y no coincidiría con
  la URI HTTPS registrada.
- Las sesiones se persisten en PostgreSQL con Spring Session JDBC
  (`spring.session.store-type=jdbc`, esquema propio por Flyway con
  `initialize-schema: never`). La cookie se llama `SESSION`; al ser una
  sesión por cookie, la protección CSRF del doble envío cubre también el
  logout. Las sesiones sobreviven a los reinicios del servicio sobre la
  misma base.
- Al completarse el login, el success handler materializa la sesión
  principal: `CuentaService.usuarioDeLogin` crea el usuario en su primer
  login identificado por el `sub` de Google (el email se guarda y se
  actualiza si cambió) o reutiliza el existente, y la sesión registra los
  atributos `usuarioId` y `sesionTipo=principal`, el índice
  `principal_name` con el id del usuario y la inactividad máxima
  configurada (`cuenta.main-session-idle-timeout`, `P30D` por defecto).
  La migración de id de sesión de la protección contra fijación ocurre
  antes del handler, así que los atributos quedan en la sesión final.
- `GET /api/sesion` lee `usuarioId` de la sesión con
  `getSession(false)`: consultar sin sesión no crea una. `POST
  /api/logout` usa el logout de Spring Security en esa ruta: invalida la
  sesión persistida del dispositivo, borra la cookie `SESSION` y responde
  `204` aunque no haya sesión.
- La web muestra «Entrar con Google» (navega a
  `/oauth2/authorization/google`) cuando `GET /api/sesion` devuelve
  `usuario: null`, y el email con «Cerrar sesión» cuando hay usuario;
  ese botón obtiene el token CSRF y envía `POST /api/logout`.

## Versiones fijadas

| Componente | Versión | Uso |
| --- | --- | --- |
| Java | 17 (OpenJDK) | Compilación y ejecución (toolchain en `build.gradle`). |
| Gradle | 8.14.5 (wrapper) | Build y gestión de dependencias; el wrapper versionado evita instalar Gradle. |
| Spring Boot | 3.5.16 | Plugin Gradle `org.springframework.boot`; fija las versiones administradas de Spring, Hibernate, Jackson, Flyway y Testcontainers. |
| Spring Web / Spring Data JPA (Hibernate 6) | administradas por Boot 3.5.16 | Controllers web y acceso JPA por `EntityManager` + HQL; no se usan repositorios Spring Data (ADR 0006). |
| Spring Security + OAuth2 Client | administrada por Boot 3.5.16 | Cadena de filtros; CSRF por doble envío (cookie `XSRF-TOKEN` + encabezado `X-XSRF-TOKEN`); `spring-boot-starter-oauth2-client` para el login OIDC con Google. |
| Spring Session JDBC (`spring-session-jdbc`) | administrada por Boot | Sesiones HTTP persistidas en `spring_session`/`spring_session_attributes` sobre el mismo PostgreSQL (ADR 0007). |
| PostgreSQL JDBC | administrada por Boot | Driver. |
| Flyway (`flyway-core` + `flyway-database-postgresql`) | administrada por Boot | Migraciones versionadas desde el inicio. |
| Testcontainers (`postgresql`, `junit-jupiter`) + `spring-boot-testcontainers` | administradas por Boot | PostgreSQL real en pruebas de integración (ADR 0004). |
| PostgreSQL (Docker) | `postgres:16.4-alpine` | Servicio de desarrollo (docker-compose) e imagen de Testcontainers. |
| mock-oauth2-server (Docker) | `ghcr.io/navikt/mock-oauth2-server:6.1.2` | Proveedor OIDC de pruebas en `src/test`: sirve la danza de autorización real con su formulario de login interactivo (ADR 0007). |
| JUnit 5 / AssertJ | administradas por Boot | Pruebas. |

## Ejecución de la base de datos

- Desarrollo: `docker-compose.yml` en la raíz levanta `postgres:16.4-alpine`
  con base `acortador`, usuario `acortador` y puerto `5432`. Las credenciales se
  pueden sobreescribir con variables de entorno (`DB_HOST`, `DB_PORT`,
  `DB_NAME`, `DB_USER`, `DB_PASSWORD`).
- Alternativa sin Docker: el perfil `hsqldb` (`application-hsqldb.yml`) conecta
  con un servidor HSQLDB local (`jdbc:hsqldb:hsql://localhost:9001/xdb`, `sa`,
  sin contraseña) levantado con `./gradlew hsqldbServer`. Flyway 11 no soporta
  HSQLDB: el esquema equivalente vive en `db/hsqldb/schema.sql` y lo aplica
  `spring.sql.init`, con `flyway` deshabilitado y `ddl-auto=none` en el perfil.
- Pruebas de integración: Testcontainers levanta `postgres:16.4-alpine`
  en tres grupos de contextos. `LinkApiHttpTest`,
  `ConcurrenciaHttpTest`, `DestinationValidationHttpTest`,
  `SeguridadCsrfHttpTest` y
  `RutasReservadasHttpTest` extienden `HttpApiFixture` y comparten un
  único contenedor (patrón singleton con `@ServiceConnection`);
  `RutasReservadasHttpTest` declara sus propias `properties` de
  `@SpringBootTest`, así que corre en un contexto Spring cacheado
  distinto pero contra el mismo contenedor. `VencimientoHttpTest` y
  `ReciclajeAliasHttpTest` agregan `ClockTestConfiguration` (reloj
  mutable) y declaran su propio `@Container` con `@ServiceConnection`.
  `EvolucionDuracionHttpTest` y `ReinicioServicioHttpTest` levantan los
  contextos programáticamente con `SpringApplicationBuilder` contra su
  propio `@Container`, con el datasource pasado por argumentos de
  arranque (sin `@ServiceConnection`). No se usa H2 (ADR 0004).

  Desde el issue #19, las pruebas del login agregan un segundo
  contenedor con el proveedor OIDC simulado
  (`ghcr.io/navikt/mock-oauth2-server:6.1.2`): el helper `GoogleSimulado`
  recorre la danza real del código de autorización
  (`/oauth2/authorize`, formulario de login interactivo, callback) y
  `HttpApiFixture` comparte el contenedor entre las suites que lo usan
  (`SesionPrincipalHttpTest`; `SesionReinicioHttpTest` levanta el suyo
  junto al PostgreSQL propio para los reinicios de contexto). Todas las
  suites que arrancan la aplicación completa declaran credenciales de
  Google de relleno (`GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` en
  `properties` o argumentos de arranque): el arranque las exige aunque
  ninguna solicitud las use.

  Desde el issue #18 todo POST de las suites envía el token CSRF del
  contrato con el helper compartido `Csrf` (`GET /api/csrf` + cookie y
  encabezado). El fixture ofrece además `nuevoClienteDispositivo()`, un
  `HttpClient` con su propio `CookieManager` por "dispositivo", que la
  regresión anónima ya usa y que es la base de las pruebas de sesión de
  la etapa 2.

## Migraciones

Flyway corre al inicio sobre la misma base de datos de la aplicación. La
migración inicial `V1__esquema_inicial.sql` crea:

- `asignacion (id BIGINT GENERATED BY DEFAULT AS IDENTITY PK, alias_codigo
  VARCHAR(16) FK -> alias.codigo, destino TEXT, creada_en TIMESTAMPTZ,
  vence_en TIMESTAMPTZ)` con `UNIQUE (alias_codigo, id)`.
- `alias (codigo VARCHAR(16) PK, asignacion_actual_id BIGINT)` con FK compuesta
  `(codigo, asignacion_actual_id) -> asignacion (alias_codigo, id)`: la base
  garantiza que la referencia actual siempre pertenece al mismo alias
  (invariante del modelo lógico). `asignacion_actual_id` admite `NULL` para el
  instante entre la reserva del alias y la creación de la asignación dentro de
  la misma transacción.
- `generador_alias (id SMALLINT CHECK id = 1 PK, proximo_indice BIGINT)` con una
  única fila: persiste el avance de la generación secuencial entre reinicios,
  sin reconstruirlo desde los alias existentes.

`spring.jpa.hibernate.ddl-auto=validate`: el esquema solo evoluciona por
migraciones versionadas, preservando las asignaciones existentes.

La migración `V2__indice_vencimiento_asignacion.sql` agrega
`ix_asignacion_vence_en` sobre `asignacion (vence_en)`: la búsqueda de
candidatos a reciclaje filtra por el vencimiento de la asignación actual
de cada alias. Solo crea el índice, sin tocar los datos existentes.

La migración `V3__usuario_y_sesiones.sql` (issue #19) agrega:

- `usuario (id BIGINT GENERATED BY DEFAULT AS IDENTITY PK, google_sub
  VARCHAR(255) UNIQUE NOT NULL, email VARCHAR(255) NOT NULL, namespace
  VARCHAR(30))`: la identidad del usuario la fija el `sub` de Google
  (único y requerido); el email se guarda en cada login y `namespace`
  queda `NULL` hasta que el usuario lo elija con `POST /api/namespace`.
- `spring_session` y `spring_session_attributes`, con el esquema que
  documenta Spring Session JDBC, con `session_id`/`principal_name`/
  `expiry_time`/`last_access_time`/`max_inactive_interval` y la tabla de
  atributos serializados, más los índices de expiración y de principal.
  El esquema lo mantiene la migración propia, no el inicializador de
  Spring Session (`initialize-schema: never`).

La migración `V4__namespace_usuario.sql` (issue #20) agrega
`uq_usuario_namespace`, una restricción única sobre `usuario.namespace`.

La migración `V5__asignacion_personal.sql` (issue #21) agrega
`asignacion_personal (id BIGINT GENERATED BY DEFAULT AS IDENTITY PK,
usuario_id BIGINT FK -> usuario.id, alias_personalizado VARCHAR(50) NOT
NULL, destino TEXT NOT NULL, creada_en TIMESTAMPTZ NOT NULL, vence_en
TIMESTAMPTZ NOT NULL)` con el índice `ix_asignacion_personal_usuario_alias`
sobre `(usuario_id, alias_personalizado)`. La asignación pertenece al
usuario y no al texto del namespace: un cambio de namespace mueve la
resolución sin tocar la fila. No lleva restricción única sobre
`(usuario_id, alias_personalizado)` porque el historial conserva todas
las asignaciones (ADR 0003) y el mismo usuario puede reusar el alias al
vencer: la unicidad de la vigente la garantiza la serialización por
usuario (ver «Coordinación transaccional»).

La migración `V8__namespace_anterior_gracia.sql` (issue #24) agrega
`nombre_namespace (namespace VARCHAR(30) PK,
usuario_id BIGINT FK -> usuario.id, grace_ends_at TIMESTAMPTZ NULL)`, el
registro único compartido por nombres actuales y anteriores para que una
reserva vigente no se duplique con un nombre actual, y
`namespace_anterior (id BIGINT GENERATED BY DEFAULT AS IDENTITY PK,
usuario_id BIGINT FK -> usuario.id, namespace VARCHAR(30) UNIQUE,
grace_ends_at TIMESTAMPTZ NOT NULL)` con índice por `grace_ends_at`. Cada fila
anterior conserva su propio fin, por lo que los cambios encadenados mantienen
períodos independientes. `grace_ends_at IS NULL` representa el nombre actual;
un instante representa una reserva anterior y queda fijado al cambiar.

El perfil `hsqldb` traduce esas tablas a mano en
`db/hsqldb/schema.sql` (mismos nombres y columnas; `BYTEA` se vuelve
`LONGVARBINARY`), como manda el ADR 0005. La restricción del namespace se
agrega con un `ALTER` tolerado por `continue-on-error`, para que aplique
tanto a bases nuevas como a las que ya existían.

## Coordinación transaccional

La creación ejecuta una única transacción (`@Transactional` de
`AcortadorService.acortar`):

1. `em.find(GeneradorAlias.class, GeneradorAlias.SINGLETON_ID,
   LockModeType.PESSIMISTIC_WRITE)` bloquea la fila única de
   `generador_alias` (`SELECT ... FOR UPDATE`) y serializa toda la
   selección: dos creaciones simultáneas no pueden tomar el mismo
   índice ni reciclar el mismo alias vencido.
2. Ya serializada, una consulta HQL sobre las entities de `mappings`
   (`em.createQuery` con `Alias` y `Asignacion`) devuelve los
   candidatos a reciclaje: los alias cuya asignación actual tiene
   `venceEn <= instante de creación`. El vínculo usa la referencia
   actual del alias, garantizada por la FK compuesta.
3. Un helper privado del service elige entre los candidatos el de
   menor longitud; a igualdad de longitud desempata por el orden
   natural del código: determinista y distingue mayúsculas, por ejemplo
   `'A'` precede a `'a'`. Si hay candidato, se reutiliza su alias sin
   consumir índice del generador.
4. Sin candidatos reutilizables, los helpers privados de enumeración
   calculan el índice del próximo código saltando los reservados
   (`reserved-routes`) y el código de ese índice sobre `alias-alphabet`,
   agotando una longitud antes de la siguiente; se actualiza
   `proximoIndice` del generador y se persiste el alias nuevo con
   `em.persist` y `em.flush`, porque la asignación IDENTITY que se
   inserta a continuación referencia `alias.codigo` por FK y la fila
   tiene que existir antes.
5. Se persiste la asignación (`em.persist`, con `creadaEn` del `Clock`
   inyectado y `venceEn = creadaEn + linkDuration`) y se actualiza
   `asignacion_actual_id` del alias en la misma transacción; las
   asignaciones anteriores del alias reciclado quedan intactas en el
   historial (ADR 0003).

La serialización cubre toda la selección: una creación nunca salta a un
alias más largo habiendo un vencido más corto disponible, ni dos creaciones
concurrentes se reparten el mismo vencido. La consulta devuelve los
candidatos y la prioridad la deciden los helpers del service en Java, no
el SQL.

El mecanismo elegido es un bloqueo pesimista sobre la fila única de
`generador_alias` (`PESSIMISTIC_WRITE` vía `em.find`, emitido como
`SELECT ... FOR UPDATE`). Detalles que cierran su suficiencia:

- **Cobertura**: el bloqueo se adquiere al inicio de la reserva dentro
  de la transacción `REQUIRED` que abrió `acortar`, por lo que se
  retiene hasta el commit del método. Quedan serializados entre sí
  la consulta de vencidos, la elección del candidato, el avance del
  índice y la escritura de asignación + referencia actual: la unidad
  completa, no solo el contador.
- **Lectores**: `resolver` corre en `@Transactional(readOnly = true)`
  y no toma bloqueos (`FOR UPDATE` no retiene lecturas en PostgreSQL).
  Con el nivel READ COMMITTED por defecto, cada consulta lee el último
  estado confirmado: la resolución lee la referencia actual del alias y
  luego la fila de esa asignación, ambas dentro de su transacción. Como
  la reasignación confirma de forma atómica el alta de la asignación y
  el cambio de referencia, un lector ve la asignación anterior completa
  (vencida: `404`) o la nueva completa (`302` al nuevo destino). Nunca
  una mezcla: la referencia solo puede apuntar a una fila ya confirmada
  (la FK compuesta lo exige) y la lectura de esa fila es atómica. Los
  lectores tampoco esperan detrás del bloqueo del generador ni retrasan
  las creaciones.
- **Fallos**: validación, reserva, asignación y referencia comparten la
  misma transacción; cualquier excepción revierte la unidad completa, así
  que no puede quedar confirmada una reserva sin asignación ni una
  referencia a una asignación inexistente. Además, por el contrato
  público no existe un fallo posterior a la reserva que sea alcanzable:
  la validación corre antes del bloqueo del generador y la persistencia
  no tiene otra restricción que una entrada ya aceptada pueda violar. La
  evidencia de atomicidad es entonces la combinación de un rechazo que
  no consume alias ni índice y una carga mixta válida/inválida que
  conserva las invariantes.
- **Suficiencia y evolución**: la sección serializada son unas pocas
  consultas indexadas por transacción; para la carga de referencia (10
  clientes simultáneos, historias cortas) la espera por el bloqueo es
  despreciable y las resoluciones no compiten por él. Si la carga
  creciera (fila «Mayor concurrencia» de
  [arquitectura y validación](arquitectura-y-validacion.md)), el cambio
  es interno del service: bloqueo por fila de alias candidato con
  `FOR UPDATE`/`SKIP LOCKED` en la consulta de vencidos, o contadores
  particionados por longitud para la generación. Las invariantes del
  modelo y las pruebas de concurrencia no cambiarían.

La evidencia HTTP de estas garantías está en `ConcurrenciaHttpTest`
(issue #6): competición por un único vencido, reparto completo de
vencidos sin emitir códigos nuevos, expansión que agota los vencidos
cortos antes de emitir más largos, atomicidad de los rechazos en carga
mixta, resolución durante la reasignación y reintento sin idempotencia.

La creación de enlaces personales (issue #21) sigue la misma disciplina
con la fila del usuario como punto de serialización:
`crearEnlacePersonal` bloquea `usuario` con `PESSIMISTIC_WRITE` antes de
consultar la asignación vigente del alias y persistir la nueva. Dos
creaciones simultáneas del mismo usuario y el mismo alias personalizado
nunca terminan con dos vigentes: la que espera lee la confirmada por la
otra y recibe `ALIAS_TAKEN`. No hay restricción única que una tercera
transacción pudiera violar, porque la unicidad es sobre la vigencia en
el instante del servidor y no sobre la tabla (el historial conserva las
vencidas). La resolución de dos segmentos corre en `readOnly` sin
bloqueos, como `resolver`: ubica al usuario por su namespace, que es
único, y lee su asignación vigente de ese alias.

El cambio de namespace (issue #24) agrega un segundo punto de
serialización sobre la fila del nombre: `elegirNamespace` bloquea al
usuario con `PESSIMISTIC_WRITE` y la fila de `nombre_namespace` con
`PESSIMISTIC_WRITE` antes de decidir si el nombre puede adoptarse. La
verificación de dueño y gracia y la escritura ocurren sobre esa misma
fila, así que una transacción no puede convertir en actual un nombre que
otra acaba de reservar entre ambas sentencias. Si la fila no existe, la
clave primaria de `nombre_namespace` cierra la carrera del alta: de dos
usuarios que piden el mismo nombre libre, exactamente uno confirma y el
otro recibe `NAMESPACE_TAKEN`. El nombre que se deja y el que se adopta
se escriben en la misma transacción; los enlaces personales no se tocan.

## Configuración de despliegue

Prefijo `shortener` (`ShortenerProperties`). Cada clave se declara en
`application.yml` como `${ENV:default}`, para que ningún valor variable
quede hardcodeado (issue #15). Las variables pueden venir del entorno o
de un `.env` opcional importado con `spring.config.import` (ruta en
`ENV_FILE`, default `.env`); el entorno real tiene precedencia y la tarea
`test` desactiva el archivo para que las pruebas usen los defaults. Los
defaults viven solo en `application.yml`: `ShortenerProperties` no los
repite y rechaza en el arranque un valor ausente o vacío, en lugar de
caer en silencio a otro valor:

- `public-base-url` (`PUBLIC_BASE_URL`, default `http://localhost:8080`):
  dirección pública del servicio usada para componer `shortUrl`. No se
  deriva de la solicitud ni se fija a localhost. Para la demo LAN se
  configura la dirección LAN, p. ej. `http://192.168.1.50:8080`.
- `link-duration` (`Duration`, `LINK_DURATION`, default `PT60M`):
  duración de las nuevas asignaciones, acortadas y personales; como
  mínimo un minuto (un número
  sin unidad son milisegundos y corta el arranque). El experimento de adaptación la
  cambia sin tocar las asignaciones existentes, cuyo `vence_en` ya está
  persistido. Evidencia ejecutada y análisis de los demás cambios
  representativos en [evidencia-evolucion.md](evidencia-evolucion.md)
  (issue #10).
- `own-origins` (lista, `OWN_ORIGINS`): orígenes propios adicionales
  (`esquema://host[:puerto]`). A ellos se suman automáticamente el origen
  de `public-base-url` y los equivalentes loopback (`localhost`,
  `127.0.0.1`, `::1`) con el mismo puerto. La comparación de enlace
  propio usa host y puerto efectivo (80/443 por defecto según esquema),
  ignorando el esquema.
- `reserved-routes` (lista, `RESERVED_ROUTES`): códigos que el generador
  salta siempre. Por defecto `api`, `error`, `login` y `oauth2`, los
  segmentos de rutas del propio servicio (las dos últimas las usa el
  cliente OAuth2 de Spring Security).
- `alias-alphabet` (`ALIAS_ALPHABET`): símbolos del espacio de códigos;
  por defecto el alfabeto de 58 acordado
  (`123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz`, sin `0`,
  `O`, `I` ni `l`). El service lo usa tanto para enumerar códigos nuevos
  como para decidir si un segmento es un código al resolver.
- `max-destination-length` (`MAX_DESTINATION_LENGTH`): largo máximo
  aceptado de la URL de destino; `8192` por defecto.
- `alias-route-pattern` (`ALIAS_ROUTE_PATTERN`): la expresión regular del
  template `{alias:regex}` del mapping de resolución, inyectada como
  placeholder `${shortener.alias-route-pattern}`, sin default propio en
  la anotación (el único está en `application.yml`). Acota qué segmentos de
  ruta llegan al service como alias (por defecto
  `[1-9A-HJ-NP-Za-km-z]{1,32}`). Adyacencia: al cambiar `ALIAS_ALPHABET`
  hay que revisar `ALIAS_ROUTE_PATTERN`, para que el patrón siga
  aceptando exactamente los códigos que el alfabeto puede emitir.

Las credenciales del proveedor OIDC y el tiempo de sesión siguen la
misma regla del entorno/`.env` pero sin default en el primer caso:

- `spring.security.oauth2.client.registration.google.client-id`
  (`GOOGLE_CLIENT_ID`, sin default) y `client-secret`
  (`GOOGLE_CLIENT_SECRET`, sin default): identifican la aplicación ante
  Google. `application.yml` declara solo el placeholder; el arranque las
  exige y corta nombrando la variable ausente (`SecurityConfig`), así el
  placeholder literal nunca llega a usarse como credencial.
- `cuenta.main-session-idle-timeout` (`Duration`, `MAIN_SESSION_IDLE_TIMEOUT`,
  default `P30D`, `CuentaProperties`): inactividad máxima de la sesión
  principal; se aplica al materializar la sesión en el login y queda
  persistida en `spring_session.max_inactive_interval`. Valores no
  positivos o nulos cortan el arranque.
- `cuenta.namespace-reserved-names` (lista, `NAMESPACE_RESERVED_NAMES`,
  default `api,login,logout,vincular,oauth2,error`, `CuentaProperties`):
  nombres que no se pueden elegir como namespace porque chocan con las
  rutas del propio servicio. Se normalizan a minúsculas y una lista vacía
  corta el arranque.
- `cuenta.namespace-grace-period` (`NAMESPACE_GRACE_PERIOD`, default `PT1H`,
  `CuentaProperties`): duración aplicada al cambiar. Su instante de fin queda
  guardado en cada namespace anterior y no cambia al modificar configuración.

## Estructura de paquetes

`io.github.spendice.linkshortener`, reorganizado al esquema de clase del
issue #15 (ADR 0006):

- `controller`: `LinkApiController` (`POST /api/links`, creación de
  enlaces acortados y personales), `RedirectController` (`GET /{alias}`
  y `GET /{namespace}/{aliasPersonalizado}`), `CsrfController`
  (`GET /api/csrf`), `SesionController` (`GET /api/sesion`,
  `POST /api/logout`), `NamespaceController` (`POST /api/namespace`,
  `GET /api/namespace/sugerencia` y `GET /api/namespace/anteriores`),
  `ApiExceptionHandler` y `dto/`
  (`CreateLinkRequest`, `CsrfTokenResponse`, `ApiErrorResponse`,
  `SesionActualResponse`, `UsuarioSesion`, `ElegirNamespaceRequest`,
  `NamespaceSugerenciaResponse`, `NamespaceAnteriorResponse`). Controllers
  pass-through:
  traducen la request, llaman al método del service y devuelven su
  resultado; el `201` serializa el record `EnlaceCreado` directo como
  body.
- `service`: `AcortadorService` con los casos de uso `acortar`,
  `resolver`, `crearEnlacePersonal` y `resolverEnlacePersonal` como
  métodos públicos y todas las reglas de negocio como
  helpers privados del mismo archivo (validación de
  destino, cálculo de vencimiento, enumeración de códigos, elección del
  reciclable, orígenes propios, formato y normalización del alias
  personalizado), y `CuentaService` (ADR 0008) con los
  casos de uso de la cuenta: `usuarioDeLogin` (alta por `sub` o reúso y
  refresco del email), `usuarioDeSesion` (usuario de la sesión actual),
  `elegirNamespace` (validación, unicidad, cambio y persistencia del
  namespace), `namespacesAnteriores` (anteriores en gracia con su fin) y
  `sugerenciaDeNamespace` (adaptación de la parte local del email).
  La inyección es `@Autowired` en campos, incluido el `EntityManager`.
  También viven acá el record de resultado
  `EnlaceCreado`, `InvalidDestinationException`,
  `InvalidNamespaceException` e `InvalidAliasPersonalizadoException`,
  cuyos `Reason` son los contratos de errores de la API.
- `mappings`: entities JPA públicas `Alias`, `Asignacion`,
  `AsignacionPersonal`, `GeneradorAlias`, `NombreNamespace`,
  `NamespaceAnterior` y `Usuario`, el modelo del
  servicio. El service las consulta y
  persiste con `EntityManager` y HQL, sin repositorios Spring Data ni
  clases de dominio paralelas que traducir. Las tablas y columnas son
  las mismas del esquema versionado.
- `config`: `ShortenerProperties` y `CuentaProperties`
  (`@ConfigurationProperties`),
  `SecurityConfig` (cadena de filtros, CSRF, firewall, rechazo CSRF,
  login OIDC y validación de las credenciales de Google) y una
  `@Configuration` mínima con el bean `Clock`.
- `resources/static`: web `index.html`, `app.js`, `styles.css`, módulo
  `qr-code.js` y biblioteca vendorizada `vendor/qrcode.js`
  (ver «QR del enlace»).

## QR del enlace (issue #7)

El QR se genera íntegramente en el cliente, sobre el `shortUrl` devuelto
por `POST /api/links` (la dirección pública del enlace, no el destino ni
una asignación histórica; ver «Código QR del enlace» en
[CONTEXT.md](../CONTEXT.md)). El backend no almacena ni sirve imágenes
QR y el cliente no recibe entities JPA: consume solo el record
`EnlaceCreado` serializado con los nombres del contrato.

- `static/vendor/qrcode.js`: qrcode-generator 1.4.4 (Kazuhiko Arase,
  MIT), vendorizada porque la demo corre en LAN sin garantía de
  Internet (nada de CDN). Produce la matriz del código con corrección
  de error `M` y versión automática. Procedencia y licencia en
  `static/vendor/README.md`.
- `static/qr-code.js`: módulo propio `QrPng` que transforma la matriz en
  un PNG de escala de grises (8 px por módulo, zona de silencio de 4
  módulos). Implementa el contenedor PNG (IHDR/IDAT/IEND con CRC-32) y
  la envoltura zlib con bloques deflate almacenados, sin canvas ni
  dependencias extra: el mismo archivo genera los bytes que se muestran
  y los que se descargan. También exporta `module.exports` para las
  pruebas de Node.
- `app.js`: tras una creación exitosa, `renderQr` arma un `Blob` con los
  bytes PNG, lo muestra en `img#qr-image` y lo ofrece como descarga en
  `a#qr-download` (`qr-<alias>.png`), revocando el object URL anterior.
  Los errores de creación mantienen `#result` oculto: nunca se renderiza
  un QR de un resultado inexistente. Enlace, vencimiento y
  `reuseNotice` se muestran junto al QR; el aviso del contrato deja
  claro que el alias puede reasignarse y no es permanente.

Verificación automatizable (`src/test/js/`): `qr-code.test.mjs` genera
el PNG con el propio `QrPng` y lo decodifica con bibliotecas
vendorizadas independientes del encoder: `UPNG.js` (PNG → píxeles,
MIT) y `jsQR` (píxeles → texto, Apache-2.0), comprobando que el
contenido es exactamente el `shortUrl`. También valida estructura
(firma, dimensiones, zona de silencio, reproducción de la matriz). Se
ejecuta con Node (`node --test` dentro de `src/test/js/`); no forma
parte de `./gradlew test`. El wiring HTTP (la página referencia y sirve los
recursos del QR) se cubre en `LinkApiHttpTest`. Procedimiento completo,
incluida la verificación visual manual, en
[verificacion-qr.md](verificacion-qr.md).

## Reloj

`AcortadorService` usa `java.time.Clock` inyectado (bean
`Clock.systemUTC()` declarado en `config`). No existe operación pública
para mover el reloj.

La vigencia se evalúa en el servidor en el instante de resolución: la
consulta compara `clock.instant()` con el `vence_en` persistido de la
asignación actual del alias. No hay tarea de limpieza: las asignaciones
vencidas permanecen en `asignacion` y `alias.asignacion_actual_id` puede
seguir apuntándolas hasta la próxima reutilización. Las visitas no
modifican la asignación: resolver no renueva el vencimiento. Las
asignaciones personales siguen la misma regla: `resolverEnlacePersonal`
filtra por `venceEn > instante del servidor` y las vencidas quedan en
`asignacion_personal` para el historial y la reutilización.

En las pruebas de integración el reloj es un bean primario mutable
(`MutableClock` vía `ClockTestConfiguration`, solo en `src/test`), lo que
permite fijar instantes exactos alrededor del vencimiento sin esperas
reales ni endpoints de control. El reinicio se prueba levantando un
segundo contexto Spring contra el mismo contenedor PostgreSQL después de
cerrar el primero (`ReinicioServicioHttpTest`).

## Alcance explícito de esta entrega

El vencimiento y el reinicio (issue #3) están verificados en
`VencimientoHttpTest` y `ReinicioServicioHttpTest`: límites temporales
exactos a nivel HTTP (un instante antes redirige; al alcanzar el límite
responde 404), visitas sin renovación de `vence_en`, validez evaluada en
el servidor al resolver, alias desconocido o vencido con la página
acordada, asignaciones vencidas conservadas en el historial y
conservación de destino, identidad, creación y vencimiento tras
reiniciar contra el mismo PostgreSQL.

La reutilización de alias (issue #5) se verifica en
`ReciclajeAliasHttpTest`, `RutasReservadasHttpTest` y
`ReinicioServicioHttpTest` sobre PostgreSQL:
reciclaje del alias vencido antes de emitir un código nuevo, prioridad de
menor longitud con desempate determinista por orden natural, expansión
agotando los 58 códigos de un carácter antes del primero de dos, salto de
los códigos reservados dentro del espacio emitido, alias vigente nunca
candidato, reasignación con identidad propia y referencia actual
actualizada conservando todo el historial, resolución al destino vigente
(un enlace o QR viejo conduce al nuevo destino), distinción de
mayúsculas, exclusividad del vencido ante creaciones concurrentes y
continuidad del reciclaje y del avance del generador tras reiniciar.

El experimento de adaptación (issue #10) se verificó en
`EvolucionDuracionHttpTest`: dos contextos sucesivos sobre el mismo
PostgreSQL (`PT60M` y luego `PT5M` solo para nuevas creaciones)
demuestran que cada asignación conserva el `vence_en` que persistió al
crearse, con la duración de entrega restituida a 60 minutos. El
procedimiento reproducible y el análisis de alias personalizados,
consola y estadísticas están en
[evidencia-evolucion.md](evidencia-evolucion.md).

Las garantías de concurrencia (issue #6) se verifican en
`ConcurrenciaHttpTest` y en las pruebas concurrentes de
`ReciclajeAliasHttpTest` y `LinkApiHttpTest`, sobre PostgreSQL y por la
API: el mismo vencido lo obtiene exactamente una creación, los vencidos
disponibles se reparten antes de emitir códigos nuevos ni más largos, la
expansión agota los vencidos cortos antes de crecer de longitud, un
rechazo no deja reserva, asignación ni referencia parciales, la
resolución durante una reasignación lee una única asignación completa
(anterior o nueva, nunca mezclada) y reintentar una creación produce
otra asignación sin idempotencia. El contrato de clientes no cambió:
misma API, mismos códigos de estado y errores.

El inicio de sesión con Google (issue #19) se verifica en
`SesionPrincipalHttpTest`, `SesionReinicioHttpTest`,
`ArranqueOidcGoogleTest` y la regresión anónima de
`SeguridadCsrfHttpTest`, contra PostgreSQL real y el proveedor OIDC
simulado por `mock-oauth2-server` en Testcontainers: danza completa del
código de autorización, alta del usuario por `sub` en el primer login y
reúso en el segundo, email persistido, sesión actual con `email`,
`namespace` vacío y `tipo` `principal`, logout que invalida solo la
sesión del dispositivo que la pide, inactividad máxima persistida en
`spring_session`, continuidad de la sesión tras reiniciar el servicio
sobre la misma base, arranque cortado con mensaje claro cuando faltan
`GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` y creación de enlaces anónima
intacta.

La elección del namespace (issue #20) se verifica en
`NamespaceHttpTest`, también sobre PostgreSQL real y el proveedor OIDC
simulado: sugerencia derivada de la parte local del email, formato y
normalización a minúsculas, nombres reservados sin distinguir mayúsculas,
unicidad sin distinguir mayúsculas, un solo namespace por usuario,
repetición idempotente del mismo nombre, `401` sin sesión y carrera entre
dos usuarios por el mismo nombre, donde exactamente uno confirma y el
otro recibe `NAMESPACE_TAKEN`. `CuentaPropertiesTest` cubre la
normalización y la obligatoriedad de la lista reservada.

Los enlaces personales con alias personalizado (issue #21) se verifican
en `EnlacePersonalHttpTest`, también sobre PostgreSQL real y el
proveedor OIDC simulado: creación con sesión y namespace y persistencia
del alias, destino, creación y vencimiento pertenecientes al usuario,
formato y normalización a minúsculas con su código propio, validación
del destino con las reglas de la etapa 1, `401` sin sesión y
`NAMESPACE_NOT_CHOSEN` sin namespace, `409` del alias vigente del mismo
usuario, reutilización tras el vencimiento con el historial conservado,
el mismo texto en namespaces de usuarios distintos, resolución de dos
segmentos `302`/`404` con `no-store` y la página acordada, el alias de
un segmento resolviendo aunque coincida con un namespace, dos
creaciones simultáneas sin duplicar la vigente y el campo vacío como
enlace acortado.

El cambio de namespace (issue #24) se verifica en `CambioNamespaceHttpTest`
con PostgreSQL real, OIDC simulado y `MutableClock`: cambios encadenados
en instantes distintos con una gracia por nombre, recuperación de un
namespace propio, reservas ajenas, liberación al fin de gracia, listado
con fin UTC y `401` sin sesión, validación de formato y nombres
reservados, exclusión ante solicitudes simultáneas —incluida la adopción
de un nombre que otra transacción confirma mientras la primera lo
intenta tomar—, conservación del vencimiento personal y un segundo
contexto con otro `cuenta.namespace-grace-period` que no altera la
gracia en curso. La página «Mi namespace» se verifica en
`src/test/js/namespace-grace.test.mjs` (`node --test`): lista cada
anterior con su tiempo restante y el botón «Volver a este» invoca el
cambio con ese nombre.

No implementa esta especificación: consola de
gestión, estadísticas de visitas ni purga del historial (analizados en
[evidencia-evolucion.md](evidencia-evolucion.md)). Tampoco las piezas de
la etapa 2 que dependen del login: sesiones vinculadas por QR y gestión de
los enlaces personales
(listado, edición, borrado) ni otros proveedores de identidad. Los complementos
Chrome y Firefox se entregan en `extensiones/` (issue #8) y el QR de la
web se resuelve en el cliente (ver «QR del enlace»). El contrato OpenAPI
ya contempla el aviso de reutilización (`reuseNotice`) para que la web lo
muestre desde el inicio.
