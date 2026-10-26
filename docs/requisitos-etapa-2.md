# Acuerdos y pendientes de la etapa 2

Fuente: segundo requerimiento del docente, recibido el 2026-10-26. Este documento lo completa con las respuestas de la elucidación; no reemplaza el contrato OpenAPI ni la especificación técnica, que todavía deben actualizarse. Los acuerdos de la [etapa 1](requisitos-etapa-1.md) siguen vigentes salvo donde este documento indica otra cosa.

Las referencias `Qn`, `Rn` y `Rn'` corresponden a las tres rondas de la entrevista de la etapa 2 iniciada el 2026-10-26. La columna «Definido por» indica si el acuerdo lo confirmó el docente o si es una decisión del equipo.

Estado: entrevista cerrada. El siguiente paso es actualizar OpenAPI y la especificación técnica y cortar el trabajo en issues; los issues los crea el equipo después de revisar este documento.

## Requerimiento recibido

> Agregar un login vía Google SSO; una vez resuelto, sigue todo igual sin login. Con login se tiene un espacio de nombres: en vez de `dominio/4324`, `dominio/juan/{link elegido}`. El espacio de nombres debe ser único. El login debe ser tipo WhatsApp: login por QR desde una instancia logueada en el celular. El espacio de nombres es editable y, al cambiarlo, hay un período de gracia durante el que se permite acceder al viejo: lo que era `/juan/123` debe funcionar como `/juani/123`. El namespace viejo queda libre después de cierto tiempo, por defecto una hora; mientras funcionan ambos, el viejo debe mostrar un mensaje tipo popup que indique «en x esto expira, la nueva dirección es y».

## Acuerdos

| Tema | Acuerdo | Origen | Definido por |
| --- | --- | --- | --- |
| Uso sin login | El flujo de la etapa 1 queda intacto en la web y en las extensiones: crear un enlace acortado no requiere iniciar sesión. El login solo agrega los namespaces. | Q1 | Equipo |
| Login con Google | Cualquier cuenta pública de Google puede iniciar sesión, no solo usuarios de prueba definidos por el equipo. Se piden solo los permisos `openid`, `email` y `profile`. | Pedido del docente, R10 | Docente |
| Dispositivos confiables y no confiables | En un dispositivo confiable se inicia sesión directamente con Google (sesión principal). En uno no confiable se usa la vinculación por QR, para no escribir las credenciales de Google ahí. Ambas opciones se ofrecen en cualquier dispositivo; el sistema no detecta el tipo de dispositivo. | Q2 | Docente |
| Instancia logueada | Es la misma web abierta en el navegador del celular; no hay aplicación nativa. | Q3 | Equipo |
| Vinculación por QR | El dispositivo que quiere entrar muestra un QR con una URL. La cámara del celular la abre, la web pide confirmación explícita («¿Iniciar sesión en este dispositivo como juan?») y, al confirmar, el primer dispositivo queda con una sesión vinculada. No hay escáner propio en la web. | Q3 | Equipo |
| Código de vinculación | Un solo uso, vence a los 60 segundos y se renueva automáticamente. El dispositivo que espera consulta el estado cada 2 segundos, sin WebSockets. | Q4 | Equipo |
| Quién aprueba | Solo una sesión principal aprueba vinculaciones; una sesión vinculada no puede vincular otros dispositivos. | R6 | Equipo |
| Duración de sesiones | Las sesiones se guardan en PostgreSQL y sobreviven a los reinicios. La sesión principal vence tras 30 días de inactividad y la vinculada tras 12 horas; ambas duraciones se configuran. | Q13, R5 | Equipo |
| Independencia de sesiones | Cerrar la sesión principal no cierra las vinculadas. No hay lista de dispositivos, pero la web ofrece «Cerrar todas las sesiones vinculadas» para cortar el acceso olvidado en un dispositivo no confiable. | Q4, R5 | Equipo |
| Namespace | Lo elige el usuario en su primer login, con una sugerencia tomada de la parte local de su email. Hasta elegirlo no puede crear enlaces personales. Cada usuario tiene a lo sumo uno. | Q7 | Equipo |
| Formato del namespace | De 3 a 30 caracteres `a-z`, `0-9` y `-`, sin guion inicial ni final. Único sin distinguir mayúsculas. Excluye palabras reservadas de las rutas del sistema (`api`, `login`, `logout`, `vincular`, `oauth2`, `error`, entre otras). | Q7 | Equipo |
| Convivencia con alias | `/juan` es un alias de un segmento y `/juan/123` un enlace personal de dos; un alias y un namespace con el mismo texto conviven. `/juan` sola no muestra un perfil. | Q7 | Equipo |
| Alias personalizado | Campo opcional del formulario con sesión iniciada. Completo crea `dominio/{namespace}/{alias personalizado}`; vacío crea un enlace acortado como en la etapa 1. Admite minúsculas, dígitos y `-`, de 1 a 50 caracteres, sin distinguir mayúsculas. Repetir uno vigente del mismo usuario responde `409`. | Q6 | Equipo |
| Vigencia de enlaces personales | Igual que cualquier asignación: 60 minutos desde la creación (`link-duration`). | Q5 | Docente |
| Reutilización del alias personalizado | Al vencer, el mismo usuario puede volver a usarlo. El alias personalizado pertenece al namespace de cada usuario: otro usuario siempre puede usar el mismo texto en su propio namespace, y también bajo el nombre `juan` cuando lo tome después del período de gracia. | R7, R7' | Equipo |
| Historial | Las asignaciones de enlaces personales se conservan como las de la etapa 1 (ADR 0003) y pertenecen al usuario. Si otra persona toma después el mismo namespace y crea el mismo alias personalizado, su asignación es distinta. | R7 | Equipo |
| Cambio de namespace | El usuario puede cambiarlo a otro nombre libre, sin límite de cambios. Sus enlaces personales conservan el alias personalizado y responden bajo el nuevo nombre. Ningún vencimiento cambia. | Requerimiento, Q8, R7 | Equipo |
| Propósito del período de gracia | Permitir que quien se arrepiente del cambio recupere su nombre anterior (por ejemplo, a la media hora). | Q5 | Docente |
| Período de gracia | Dura `namespace-grace-period` (por defecto una hora), calculado una sola vez al cambiar; cambiar la configuración no altera las gracias en curso. Mientras dura, el namespace anterior queda reservado: nadie más puede tomarlo y su usuario puede volver a él. Con cambios encadenados (`juan → juani → juanito`), cada nombre tiene su propia gracia y todos llevan al namespace actual. | Requerimiento, Q8, Q11 | Docente; los encadenamientos, equipo |
| Qué resuelve el namespace anterior | Cualquier enlace personal vigente del usuario, incluso los creados después del cambio: si existe `juani/456`, `/juan/456` también funciona. | R2 | Equipo |
| Aviso en el namespace anterior | La dirección anterior no redirige directamente: muestra una página con un modal «Esta dirección deja de funcionar a las 16:30 (en 42 min). La nueva dirección es …/juani/123», con cuenta regresiva, botón para copiar la nueva dirección y botón «Ir ahora». Redirige sola tras `namespace-notice-delay` (por defecto 5 segundos). La hora mostrada es la que ocurra primero entre el fin del período de gracia y el vencimiento del enlace. La dirección nueva redirige directamente, como siempre. | Q10, R1 | Docente; la página previa, equipo |
| Fin del período de gracia | El nombre queda libre para cualquier usuario y `/juan/123` responde el 404 acordado. Si otro usuario toma `juan` y crea `123`, los enlaces y QR viejos conducen a su destino, igual que la reasignación de alias aceptada en la etapa 1. | Q9 | Docente |
| Página «Mi namespace» | Muestra el namespace actual con un formulario para cambiarlo, los namespaces anteriores en período de gracia con su tiempo restante y un botón «Volver a este», y el botón para cerrar las sesiones vinculadas. | R11 | Equipo |
| Extensiones | Incorporan el login y los enlaces personales: muestran el namespace, suman el campo opcional de alias personalizado y ofrecen «Cerrar sesión». Elegir y cambiar el namespace se hace solo en la web. Si el usuario todavía no eligió uno, la extensión le indica que lo haga en la web. | Q12, R4 | Docente; el alcance, equipo |
| Sesión de las extensiones | La extensión reutiliza la sesión de la web en el mismo navegador. Antes de implementarla se prueba en Chrome y en Firefox que el popup reciba la cookie y pueda hacer un POST autenticado; si falla en alguno, la extensión se trata como otro dispositivo con su propia vinculación por QR. | R3, R3' | Equipo |
| Dirección pública | Google exige URIs de redirección HTTPS y no admite IPs directas, por lo que la demo LAN por `http://<ip>` no alcanza. `PUBLIC_BASE_URL` pasa a ser una URL HTTPS estable publicada con Tailscale Funnel, también para los QR de los enlaces acortados. | Q14, R9 | Equipo |
| Proyecto de Google Cloud | Lo crea un integrante con su cuenta personal, de tipo «Externo» y publicado «En producción». Las credenciales van en `.env` y nunca en el repositorio. | R10 | Equipo |
| Configuración | Todo valor nuevo se declara en la configuración con su variable de entorno y puede definirse en `.env`, como en la etapa 1. | Q11 | Equipo |
| Arquitectura | Un segundo service para usuarios, namespaces y vinculación; ver el ADR 0008. | Q15 | Equipo |
| Pruebas automáticas | Las suites HTTP inician sesión contra un proveedor OIDC simulado en Testcontainers; el Google real se verifica solo en la prueba manual. Ver el ADR 0007. | Q16, R8, R8' | Equipo |
| Prueba final | Un recorrido manual de punta a punta, hecho por un integrante con su cuenta personal de Google desde un navegador común, en un issue `ready-for-human` como el #12. | Q16, Q17 | Equipo |

## Configuración nueva

Nombres tentativos; la especificación técnica fija los definitivos. Todos admiten valor en `.env`.

| Clave | Variable | Default |
| --- | --- | --- |
| `namespace-grace-period` | `NAMESPACE_GRACE_PERIOD` | `PT1H` |
| `namespace-notice-delay` | `NAMESPACE_NOTICE_DELAY` | `PT5S` |
| `namespace-reserved-names` | `NAMESPACE_RESERVED_NAMES` | rutas del sistema |
| `main-session-idle-timeout` | `MAIN_SESSION_IDLE_TIMEOUT` | `P30D` |
| `linked-session-idle-timeout` | `LINKED_SESSION_IDLE_TIMEOUT` | `PT12H` |
| `link-code-ttl` | `LINK_CODE_TTL` | `PT60S` |
| Registro OIDC de Google | `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | sin default; obligatorias |

## Escenarios derivados de los acuerdos

- Sin sesión, la web y las extensiones crean enlaces acortados exactamente como en la etapa 1.
- Con sesión y el alias personalizado vacío, se crea un enlace acortado, no un enlace personal.
- Una cuenta de Google que nunca usó el servicio puede iniciar sesión sin que el equipo la haya registrado.
- En el primer login el usuario debe elegir un namespace antes de crear enlaces personales.
- Dos usuarios no pueden tener el mismo namespace, aunque lo escriban con distintas mayúsculas.
- Un namespace igual a una ruta reservada se rechaza.
- `/juan/123` redirige al destino mientras su asignación esté vigente y responde 404 al vencer.
- Repetir un alias personalizado vigente del mismo usuario responde 409; otro usuario puede usar el mismo texto en su namespace.
- Un QR de vinculación usado o vencido no inicia sesión.
- Una sesión vinculada no puede aprobar otra vinculación.
- Cerrar las sesiones vinculadas desde la web desloguea los dispositivos vinculados, no la sesión principal.
- Las sesiones siguen activas después de reiniciar el servicio.
- Después de cambiar `juan` por `juani`, `/juani/123` redirige directamente y `/juan/123` muestra el aviso y redirige tras la demora configurada.
- El aviso muestra la hora que ocurra primero entre el fin de la gracia y el vencimiento del enlace.
- Un enlace creado como `juani/456` después del cambio también responde como `/juan/456` durante la gracia.
- Durante la gracia, otro usuario no puede tomar `juan`; su antiguo usuario sí puede volver a él.
- Con `juan → juani → juanito`, `/juan/...` y `/juani/...` llevan a `juanito` hasta el fin de sus gracias respectivas.
- Al terminar la gracia, `/juan/123` responde 404 y otro usuario puede tomar `juan`; si crea `123`, los QR viejos llevan a su destino.
- Cambiar de namespace no modifica el vencimiento de ninguna asignación.
- La extensión con sesión iniciada en la web del mismo navegador muestra el namespace y crea enlaces personales.

## Fuera de alcance

- Lista de enlaces personales, edición o borrado de enlaces.
- Lista de dispositivos vinculados; solo existe el cierre de todas las sesiones vinculadas.
- Escáner de QR dentro de la web.
- Elegir o cambiar el namespace desde las extensiones.
- Otros proveedores de identidad además de Google.
- Borrado de usuarios o de sus datos.

## Especificación técnica siguiente

Estas tareas no cambian los acuerdos de producto:

- Actualizar `openapi.yaml`: alias personalizado opcional en `POST /api/links`, respuesta `409`, resolución de dos segmentos, página de aviso, endpoints de sesión, namespace y vinculación.
- Definir el modelo persistido de usuarios, namespaces anteriores, enlaces personales y códigos de vinculación, con migraciones Flyway y su traducción manual en `db/hsqldb/schema.sql` (ADR 0005), incluidas las tablas de Spring Session.
- Fijar el manejo de CSRF en la web y en las extensiones.
- Hacer la prueba de cookies de las extensiones en Chrome y Firefox antes de implementar su login.
- Preparar Tailscale Funnel (habilitarlo en la tailnet y activar los certificados HTTPS) y registrar su URL como URI de redirección en Google Cloud.
- Actualizar `.env.example`, la tabla de configuración del README y la especificación técnica.

## Verificación

- Suites HTTP con PostgreSQL real y proveedor OIDC simulado en Testcontainers, una por regla: login y primer usuario, namespace único y reservado, enlaces personales y alias repetido, vinculación por QR (dos clientes HTTP con cookies separadas), sesiones tras reinicio, cambio de namespace, gracia, aviso, fin de la gracia y reutilización del nombre.
- Pruebas `node --test` de las extensiones para el estado con y sin sesión.
- Prueba final manual con Google real, desde un navegador común y un celular, con `NAMESPACE_GRACE_PERIOD` corto en `.env` para observar el fin de la gracia:
  1. Sin sesión, crear un enlace acortado y verificar que redirige.
  2. En el celular, iniciar sesión con Google y elegir `juan`.
  3. En la computadora, abrir el login, escanear el QR con el celular, confirmar y verificar que la computadora queda con sesión vinculada.
  4. Crear `juan/123` desde la computadora, verificar la redirección y escanear su QR.
  5. Con otra cuenta de Google, intentar tomar `juan` y verificar el rechazo.
  6. Cambiar `juan` por `juani`: `/juani/123` redirige directamente; `/juan/123` muestra el aviso con cuenta regresiva y la nueva dirección, y redirige.
  7. Con la otra cuenta, intentar tomar `juan` durante la gracia y verificar el rechazo.
  8. Al terminar la gracia, `/juan/123` responde 404 y la otra cuenta puede tomar `juan`.
  9. Crear un enlace personal desde la extensión en Chrome y en Firefox.
  10. Cerrar las sesiones vinculadas desde el celular y verificar que la computadora queda sin sesión; sin sesión, crear un enlace acortado sigue funcionando.

## Documentos relacionados

- [Glosario](../CONTEXT.md).
- [Acuerdos de la etapa 1](requisitos-etapa-1.md).
- [ADR 0003: conservar historial](adr/0003-conservar-historial-asignaciones.md).
- [ADR 0005: Gradle y perfil HSQLDB](adr/0005-gradle-y-perfil-hsqldb.md).
- [ADR 0006: service único con HQL sobre mappings](adr/0006-service-unico-hql-mappings.md).
- [ADR 0007: login con Google y sesiones persistidas](adr/0007-login-google-y-sesiones-persistidas.md).
- [ADR 0008: segundo service para cuentas](adr/0008-segundo-service-cuentas.md).
