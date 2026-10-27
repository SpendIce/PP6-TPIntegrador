# Evidencia de la elección y el cambio de namespace (issues #20 y #24)

Verificación de los Changes `issue-20-elegir-namespace` y
`issue-24-cambio-namespace-gracia`, contra los criterios de sus specs.
Las decisiones están en el
[diseño del cambio](../.atl/changes/issue-24-cambio-namespace-gracia/design.md);
el contrato en `openapi.yaml` y la sección de namespace de
[especificacion-tecnica.md](especificacion-tecnica.md).

## Qué se implementó

- `POST /api/namespace`: elige el namespace del usuario de la sesión.
  Normaliza a minúsculas, valida de 3 a 30 caracteres `a-z`, `0-9` y `-`
  sin guion inicial ni final, y rechaza nombres reservados y nombres ya
  tomados sin distinguir mayúsculas. Si el usuario ya eligió, el mismo
  endpoint cambia el nombre: el anterior queda registrado en gracia
  (`cuenta.namespace-grace-period`, `NAMESPACE_GRACE_PERIOD`), reservado
  mientras dure y recuperable por su dueño hasta el fin de gracia; los
  enlaces personales no se modifican.
  Respuestas de rechazo con `{error, message}` y código propio:
  `NAMESPACE_INVALID` y `NAMESPACE_RESERVED` (`400`), `NAMESPACE_TAKEN`
  (`409`, cubre tomados y en gracia ajenos); sin sesión,
  `NOT_AUTHENTICATED` (`401`). Exige CSRF como todo POST.
- `GET /api/namespace/sugerencia`: sugiere un nombre derivado de la
  parte local del email y adaptado al formato; no lo reserva.
- `GET /api/sesion` devuelve el namespace elegido y, mientras no exista,
  la sugerencia para la pantalla del primer login.
- Configuración `cuenta.namespace-reserved-names`
  (`NAMESPACE_RESERVED_NAMES`), por defecto
  `api,login,logout,vincular,oauth2,error`.
- Migración `V4__namespace_usuario.sql` con
  `uq_usuario_namespace`; la misma restricción se traduce a mano en
  `db/hsqldb/schema.sql` (ADR 0005). El cambio con gracia vive en
  `V8__namespace_anterior_gracia.sql`, numerada después de las
  migraciones V6/V7 del issue #23.
- Web: con sesión y sin namespace aparece el formulario con la
  sugerencia precargada; los errores del contrato se muestran y el
  namespace elegido se suma al email en la cabecera. Con namespace
  elegido, «Mi namespace» muestra el actual, lista los anteriores en
  gracia con el tiempo restante, ofrece «Volver a este» y deja el botón
  de cierre de sesiones vinculadas deshabilitado hasta que se integre el
  issue #23.

## Concurrencia

La elección bloquea la fila del usuario con `PESSIMISTIC_WRITE` y la
fila del nombre con `PESSIMISTIC_WRITE` cuando ya existe; la
verificación de dueño y gracia y la adopción ocurren sobre esa misma
fila, así que nadie pisa una reserva confirmada por otra transacción.
Si la fila no existe, la restricción única cierra la carrera entre dos
usuarios por el mismo nombre: el segundo recibe la violación, el service
la traduce a `NAMESPACE_TAKEN` y la transacción se revierte. Nunca
quedan ambos con el nombre, igual que la reserva de alias de
`ConcurrenciaHttpTest`.

## Pruebas automatizadas

- `NamespaceHttpTest` (extiende `HttpApiFixture`, PostgreSQL real y
  `mock-oauth2-server` en Testcontainers): sugerencia desde el email,
  formato inválido, reservados sin distinguir mayúsculas, tomado sin
  distinguir mayúsculas, repetición idempotente, `401` sin sesión y
  carrera simultánea de dos usuarios.
- `CambioNamespaceHttpTest`: cambio con gracia y recuperación del nombre
  anterior, encadenamiento en instantes distintos con una gracia que
  vence mientras la otra sigue, `401` sin sesión en el listado de
  anteriores, un segundo contexto con otro `namespace-grace-period` que
  no altera la reserva en curso, el solapamiento real de dos altas y el
  de una adopción contra un cambio forzados con
  `InspeccionNombreNamespace` — el perdedor recibe `NAMESPACE_TAKEN`
  (`409`) —, y conservación del vencimiento de los enlaces personales.
- `src/test/js/namespace-grace.test.mjs` (`node --test`): la página
  muestra cada namespace anterior con su tiempo restante y «Volver a
  este» devuelve el nombre para pedir el cambio.
- `CuentaPropertiesTest`: normalización y obligatoriedad de la lista de
  nombres reservados, y validación del período de gracia positivo.

## Ejecutado

Corrida del 2026-10-27 con el oráculo del Project:

```text
./gradlew --no-daemon --console=plain test
BUILD SUCCESSFUL   (127 pruebas, 0 fallos, 22 suites)

cd src/test/js && node --test    -> pass 9
cd extensiones && node --test    -> pass 42
```

Cubre las suites HTTP de la etapa 1, las de login de #19 y las nuevas de
namespace, contra PostgreSQL real. No se desactivó ni adaptó ninguna
prueba al entorno.
