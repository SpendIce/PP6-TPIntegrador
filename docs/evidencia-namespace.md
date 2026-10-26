# Evidencia de la elección del namespace (issue #20)

Verificación del Change `issue-20-elegir-namespace`, contra los criterios
de su spec. Las decisiones están en el
[diseño](../.atl/changes/issue-20-elegir-namespace/design.md); el contrato
en `openapi.yaml` y «Elección del namespace» de
[especificacion-tecnica.md](especificacion-tecnica.md).

## Qué se implementó

- `POST /api/namespace`: elige el namespace del usuario de la sesión.
  Normaliza a minúsculas, valida de 3 a 30 caracteres `a-z`, `0-9` y `-`
  sin guion inicial ni final, rechaza nombres reservados y nombres ya
  tomados sin distinguir mayúsculas, y no permite que un usuario que ya
  eligió cambie de nombre por esta vía (solo repetir el mismo).
  Respuestas de rechazo con `{error, message}` y código propio:
  `NAMESPACE_INVALID` y `NAMESPACE_RESERVED` (`400`), `NAMESPACE_TAKEN`
  y `NAMESPACE_ALREADY_CHOSEN` (`409`); sin sesión, `NOT_AUTHENTICATED`
  (`401`). Exige CSRF como todo POST.
- `GET /api/namespace/sugerencia`: sugiere un nombre derivado de la
  parte local del email y adaptado al formato; no lo reserva.
- `GET /api/sesion` devuelve el namespace elegido y, mientras no exista,
  la sugerencia para la pantalla del primer login.
- Configuración `cuenta.namespace-reserved-names`
  (`NAMESPACE_RESERVED_NAMES`), por defecto
  `api,login,logout,vincular,oauth2,error`.
- Migración `V4__namespace_usuario.sql` con
  `uq_usuario_namespace`; la misma restricción se traduce a mano en
  `db/hsqldb/schema.sql` (ADR 0005).
- Web: con sesión y sin namespace aparece el formulario con la
  sugerencia precargada; los errores del contrato se muestran y el
  namespace elegido se suma al email en la cabecera.

## Concurrencia

La elección bloquea la fila del usuario con `PESSIMISTIC_WRITE` y la
restricción única cierra la carrera entre dos usuarios por el mismo
nombre: el segundo recibe la violación, el service la traduce a
`NAMESPACE_TAKEN` y la transacción se revierte. Nunca quedan ambos con
el nombre, igual que la reserva de alias de `ConcurrenciaHttpTest`.

## Pruebas automatizadas

- `NamespaceHttpTest` (extiende `HttpApiFixture`, PostgreSQL real y
  `mock-oauth2-server` en Testcontainers): sugerencia desde el email,
  formato inválido, reservados sin distinguir mayúsculas, tomado sin
  distinguir mayúsculas, repetición idempotente, usuario que ya eligió,
  `401` sin sesión y carrera simultánea de dos usuarios.
- `CuentaPropertiesTest`: normalización y obligatoriedad de la lista de
  nombres reservados.

## Ejecutado

Corrida del 2026-10-26 con el oráculo del Project:

```text
./gradlew --no-daemon --console=plain test
BUILD SUCCESSFUL   (99 pruebas, 0 fallos, 19 suites)

cd src/test/js && node --test    -> pass 5
cd extensiones && node --test    -> pass 42
```

Cubre las suites HTTP de la etapa 1, las de login de #19 y las nuevas de
namespace, contra PostgreSQL real. No se desactivó ni adaptó ninguna
prueba al entorno.
