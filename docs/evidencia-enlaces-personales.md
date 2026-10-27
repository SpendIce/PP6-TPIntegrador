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

**Corrida completa.** `./gradlew --no-daemon --console=plain test` en el
sandbox de verificación (socket del daemon de Docker montado): BUILD
SUCCESSFUL, exit 0, 118 pruebas y 0 fallos en 21 suites. `EnlacePersonalHttpTest`
aporta 15 pruebas en verde (`build/test-results/test/TEST-…EnlacePersonalHttpTest.xml`).

## Verificación de apoyo sobre HSQLDB

Sin conexión a Docker se verificó el mismo contrato sobre HSQLDB en memoria
(perfil `hsqldb`, ADR 0005):

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
(la suite del contrato es la de Testcontainers, que corre completa en el
oráculo).

## Revisión

El diff `main...HEAD` pasó por una revisión de estándares y otra de spec.
La de spec no encontró criterios faltantes ni trabajo fuera del Change.
La de estándares encontró documentación desactualizada y una invariante
rota del fixture, ya corregidas en esta misma rama:

- Los Javadoc de `AcortadorService`, `SecurityConfig` y
  `RedirectController` y el encabezado de `db/hsqldb/schema.sql` volvieron
  a describir el estado real (cuatro casos de uso, el alias personalizado
  exige sesión, la ruta de dos segmentos y el esquema V1 a V5).
- El segmento personal de la ruta se renombró a `{aliasPersonalizado}`,
  coherente con `openapi.yaml`, cuyos patrones de ruta quedaron ajustados
  al contrato (namespace `3-30`, alias `1-50`, ambos sin distinguir
  mayúsculas).
- `seedAliasVigente` ahora avanza `generador_alias.proximo_indice` con
  `GREATEST`, la invariante «un alias existente cuenta como ya emitido»
  del seed de `ConcurrenciaHttpTest`; `CodigosDeAlias.indiceDe` es la
  inversa de `codigoEn`.
- Tras el renombre se verificó de nuevo sobre HSQLDB en memoria que
  `GET /{namespace}/{aliasPersonalizado}` responde `404` con la página
  acordada y `no-store` para un namespace inexistente (chequeo también
  retirado del árbol).
