# Evidencia de los enlaces personales con alias personalizado (issue #21)

Verificación del Change `issue-21-enlaces-personales`, contra los criterios
de su spec. Las decisiones están en el
[diseño](../.atl/changes/issue-21-enlaces-personales/design.md); el contrato
en `openapi.yaml` y «Contrato» de
[especificacion-tecnica.md](especificacion-tecnica.md).

## Qué se implementó

- `POST /api/links` acepta `aliasPersonalizado` opcional: ausente, `null`
  o vacío (también con sesión) sigue el flujo de la etapa 1. Completo crea
  el enlace personal `/{namespace}/{alias}` del usuario de la sesión:
  normaliza a minúsculas, valida de 1 a 50 caracteres `a-z`, `0-9` y `-`,
  y pasa el destino por la misma validación del enlace acortado. Rechazos
  con `{error, message}` y código propio: `ALIAS_INVALID` (`400`),
  `NAMESPACE_NOT_CHOSEN` (`409`, sesión sin namespace elegido),
  `ALIAS_TAKEN` (`409`, alias vigente del mismo usuario) y
  `NOT_AUTHENTICATED` (`401`, sin sesión).
- `GET /{namespace}/{aliasPersonalizado}` resuelve la asignación vigente
  del usuario dueño del namespace: `302` al destino o `404` con «Este
  enlace no existe o venció», ambos con `Cache-Control: no-store` y sin
  distinguir mayúsculas en ninguno de los dos segmentos. El alias de un
  segmento sigue resolviendo los códigos de la etapa 1 aunque coincidan
  con un namespace (`/juan` nunca muestra un perfil).
- `asignacion_personal` (migración `V5__asignacion_personal.sql` y su
  traducción en `db/hsqldb/schema.sql`, ADR 0005): pertenece al usuario,
  no al texto del namespace, y guarda alias personalizado, destino,
  creación y vencimiento. El historial se conserva (ADR 0003): al vencer,
  el mismo usuario puede reusar el alias y quedan ambas asignaciones.
- La unicidad del alias vigente por usuario se serializa con
  `PESSIMISTIC_WRITE` sobre la fila del usuario, la misma disciplina que
  la fila del generador para los alias de un segmento: dos creaciones
  simultáneas del mismo usuario y alias nunca terminan con dos vigentes,
  sin restricción única que una vencida pudiera violar.
- Web: con sesión principal y namespace elegido el formulario suma el
  campo opcional de alias personalizado; completo se envía con el destino
  y vacío crea el enlace acortado de siempre.

## Verificación

La suite del contrato es `EnlacePersonalHttpTest` (15 pruebas sobre
PostgreSQL real y el proveedor OIDC simulado, ADR 0004 y ADR 0007,
extendiendo `HttpApiFixture`): cubre los 15 criterios de aceptación,
incluida la carrera entre dos dispositivos del mismo usuario.

**Limitación del entorno.** Este sandbox no tiene el socket del daemon de
Docker (`/var/run/docker.sock` ausente, sin binario `docker` ni socket
TCP), así que ninguna suite de Testcontainers puede arrancar: en la
corrida completa de `./gradlew --no-daemon --console=plain test` fallaron
83 pruebas, todas con `IllegalStateException` en
`DockerClientProviderStrategy` («Could not find a valid Docker
environment»), incluidas las 15 de `EnlacePersonalHttpTest`. Es el mismo
fallo de entorno que afecta a todas las suites previas del fixture; las
23 pruebas que no necesitan contenedores pasaron.

Para no entregar sin evidencia real se verificó el mismo contrato sobre
HSQLDB en memoria (perfil `hsqldb`, ADR 0005), que sí corre sin Docker:

- Un chequeo del service con las entities, HQL y transacciones reales:
  creación, normalización a minúsculas, resolución sin distinguir
  mayúsculas en ambos segmentos, `ALIAS_TAKEN` (también con otra
  capitalización), `ALIAS_INVALID` por formato, validación de destino de
  la etapa 1, `NAMESPACE_NOT_CHOSEN`, reuso tras el vencimiento con las
  dos asignaciones conservadas, mismo texto en namespaces de dos
  usuarios y la serialización de dos creaciones simultáneas (una sola
  asignación vigente).
- Un chequeo HTTP completo arrancando la aplicación en puerto aleatorio
  contra `jdbc:hsqldb:mem`: `POST /api/links` con CSRF real y la sesión
  fabricada por SQL en `spring_session` (los mismos atributos que
  materializa el login — la cookie `SESSION` viaja en Base64 y el
  `HttpCookie` a mano va en versión 0, el formato Netscape que parsea el
  servidor): `201` con `shortUrl` `/{namespace}/{alias}`, `302` con
  `Location` y `no-store`, `404` con la página acordada, `401` sin
  sesión, `409` `NAMESPACE_NOT_CHOSEN`/`ALIAS_TAKEN`, `400`
  `ALIAS_INVALID`/`UNSUPPORTED_SCHEME`, campo vacío como enlace
  acortado, alias de la etapa 1 resolviendo bajo un namespace de igual
  texto y dos POST simultáneos del mismo usuario con una sola asignación
  vigente.

Los dos chequeos pasaron en verde; eran de apoyo y se retiraron del árbol
(la suite del contrato es la de Testcontainers). En un entorno con Docker
la corrida a repetir es la del spec: `./gradlew --no-daemon
--console=plain test`.
