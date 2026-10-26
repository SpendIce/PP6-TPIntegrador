# Evidencia del login con Google y la sesión principal (issue #19)

Verificación del Change
`issue-19-login-google-sesion-principal`, contra los criterios de su
spec. La decisión de arquitectura está en
[ADR 0007](adr/0007-login-google-y-sesiones-persistidas.md) y
[ADR 0008](adr/0008-segundo-service-cuentas.md); el contrato en
`openapi.yaml` y «Sesiones y login con Google» de
[especificacion-tecnica.md](especificacion-tecnica.md).

## Qué se implementó

- Registro OIDC `google` con alcances `openid email profile`;
  `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` se leen del entorno o del
  `.env` sin default y su ausencia corta el arranque nombrando la
  variable (`SecurityConfig.credencialesGoogleObligatorias`: el binding
  deja el placeholder `${...}` como literal, así que también se rechaza
  ese valor).
- `CuentaService.usuarioDeLogin`: crea el `usuario` por `sub` en el
  primer login y lo reutiliza en los siguientes (email refrescado si
  cambió). La unicidad la garantiza `uq_usuario_google_sub` de la
  migración `V3`.
- `GET /api/sesion`: `{"usuario": null}` sin sesión (y sin crear una);
  con sesión, `{email, namespace: "", tipo: "principal"}`.
- `POST /api/logout`: logout de Spring Security con CSRF por doble
  envío; invalida la sesión persistida del dispositivo y borra la cookie
  `SESSION`; `204` también sin sesión.
- Sesiones en PostgreSQL con Spring Session JDBC
  (`spring_session`/`spring_session_attributes` de `V3`, mismas tablas
  traducidas a mano en `db/hsqldb/schema.sql`, ADR 0005). Atributos
  `usuarioId` y `sesionTipo`, índice `principal_name` e inactividad
  máxima de `cuenta.main-session-idle-timeout` (`P30D` por defecto,
  `MAIN_SESSION_IDLE_TIMEOUT`).
- Web: «Entrar con Google» navega a `/oauth2/authorization/google`; con
  sesión se muestran email y «Cerrar sesión».
- Flujo anónimo intacto: `POST /api/links` sigue sin sesión ni
  credenciales.

## Pruebas automatizadas

- `SesionPrincipalHttpTest` (extiende `HttpApiFixture`, PostgreSQL real
  + `mock-oauth2-server` en Testcontainers): danza completa del código
  de autorización contra el proveedor simulado (formulario de login
  interactivo con `username`/`claims`), alta y reúso del usuario, email
  persistido, contrato de `/api/sesion`, `max_inactive_interval` leído
  de `spring_session`, atributos persistidos, logout por dispositivo,
  `204` sin sesión, `403 CSRF_TOKEN_INVALID` sin token y regresión de
  creación anónima.
- `SesionReinicioHttpTest`: dos contextos sucesivos sobre el mismo
  PostgreSQL (antecedente `ReinicioServicioHttpTest`); la cookie
  `SESSION` sigue identificando la sesión tras el reinicio.
- `ArranqueOidcGoogleTest` (HSQLDB en memoria, sin Docker): corte de
  arranque con el nombre de la variable faltante y resolución de las
  credenciales desde un `.env` (`ENV_FILE`).
- `HsqldbArranqueTest`: el perfil `hsqldb` arranca con las tablas
  traducidas de `usuario` y Spring Session.
- Las suites existentes que arrancan contextos declaran credenciales de
  relleno (`GoogleSimulado.CLIENT_ID`/`CLIENT_SECRET`): el arranque las
  exige aunque la solicitud nunca llegue a Google.

## Ejecutado

Corrida del 2026-10-26 sobre el head del Change, con el oráculo del Project
(`./gradlew --no-daemon --console=plain test`): PostgreSQL y
`mock-oauth2-server` en Testcontainers sobre Docker real.

```text
./gradlew --no-daemon --console=plain test
BUILD SUCCESSFUL   (89 pruebas, 0 fallos, 18 suites)

cd src/test/js && node --test    -> pass 5
cd extensiones && node --test    -> pass 42
```

Cubre las suites nuevas de sesión y las 12 suites HTTP preexistentes de
la etapa 1, todas contra PostgreSQL real. Ninguna prueba se desactivó ni
se adaptó al entorno.
