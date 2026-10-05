# Demo y consolidación de la etapa 1 (issue #11)

Cierre integrado de la primera etapa del acortador, según los criterios
del issue #11 y de la especificación padre (issue #1). Este documento
consolida el estado final de contratos, modelo de datos y decisiones,
mapea dónde está demostrado cada comportamiento, declara los límites
reales de la evidencia y deja el procedimiento de demo paso a paso: la
parte automatizada ya ejecutada (con artefactos en
`docs/evidencia-demo/`) y la parte física como checklist verificable
para el humano.

## Estado final del contrato

[openapi.yaml](../openapi.yaml) es el contrato vigente, sin cambios en
esta integración:

- `POST /api/links` — recibe `{"destination": "<url>"}`; `201` con
  `shortUrl` (dirección pública configurada + alias), `alias`,
  `expiresAt` y `reuseNotice`. `400` con `{error, message}`:
  `EMPTY_DESTINATION`, `DESTINATION_TOO_LONG` (> 8.192),
  `MALFORMED_DESTINATION` (formato inválido, espacios en crudo,
  `%` inválido, Unicode sin codificar), `UNSUPPORTED_SCHEME`
  (no http/https), `OWN_ORIGIN` (orígenes propios y equivalentes),
  `INVALID_REQUEST` (cuerpo/JSON fuera del contrato). Ningún rechazo
  crea asignación ni consume alias.
- `GET /{alias}` — `302` con `Location` al destino conservado verbatim
  si hay asignación vigente; `404` con la página «Este enlace no existe
  o venció» en caso contrario. Ambas con `Cache-Control: no-store`.
- Sin cuentas, sin idempotencia ni deduplicación, sin endpoints de
  gestión: fuera de alcance acordado para esta etapa.

## Modelo de datos (PostgreSQL, Flyway)

- `asignacion (id, alias_codigo FK, destino, creada_en, vence_en)`:
  historial completo; `UNIQUE (alias_codigo, id)`.
- `alias (codigo PK, asignacion_actual_id)` con FK compuesta hacia
  `(asignacion.alias_codigo, asignacion.id)`: la referencia actual
  siempre pertenece al mismo alias.
- `generador_alias (id=1, proximo_indice)`: avance persistido del
  generador secuencial, continuo tras reinicios.
- Migraciones: `V1__esquema_inicial.sql`, `V2__indice_vencimiento_asignacion.sql`.
  `ddl-auto=validate`: el esquema solo evoluciona por migraciones.

## Decisiones vigentes

- [ADR 0001](adr/0001-monolito-modular.md): monolito modular, dominio
  sin imports de Spring/JPA/HTTP.
- [ADR 0002](adr/0002-alias-reutilizable.md): identidad de asignación
  separada del alias; reciclaje de vencidos, más cortos primero;
  alfabeto de 58 símbolos; agotar una longitud antes de la siguiente.
- [ADR 0003](adr/0003-conservar-historial-asignaciones.md): historial
  conservado al reasignar; sin purga ni visitas.
- [ADR 0004](adr/0004-postgresql-en-integracion.md): PostgreSQL en la
  aplicación y en las pruebas (Testcontainers, sin H2).
- Especificación técnica ([especificacion-tecnica.md](especificacion-tecnica.md)):
  coordinación transaccional con `SELECT … FOR UPDATE` sobre la fila del
  generador (serializa selección + asignación + referencia), lectores sin
  bloqueos, QR generado en el cliente (`static/qr-code.js` +
  `vendor/qrcode.js`, MIT), complementos MV3/MV2 con código compartido.

Las especificaciones/prompts de ingeniería que guiaron la generación con
IA son los documentos versionados del repo: consigna
([TP_PP6_v1.0.md](../TP_PP6_v1.0.md)), especificación padre (issue #1 de
GitHub), [requisitos-etapa-1.md](requisitos-etapa-1.md),
[arquitectura-y-validacion.md](arquitectura-y-validacion.md),
[especificacion-tecnica.md](especificacion-tecnica.md), openapi.yaml y
los ADR; las issues #2–#10 documentan cada incremento.

## Mapa de evidencia

| Comportamiento / AC | Evidencia automatizada | Evidencia de la demo |
| --- | --- | --- |
| Creación con resultado completo (enlace, vencimiento, aviso) | `LinkApiHttpTest`, `CreateLinkTest` | Demo fase A (`salida-demo.txt`): 201 con `shortUrl` LAN, `expiresAt` +60 min, `reuseNotice` |
| Validación de destinos (LAN, credenciales, params, fragmento, 8.192, rechazos, orígenes propios equivalentes) | `DestinationValidationHttpTest`, `DestinationValidatorTest` | Demo fase A: aceptados con resolución verbatim y rechazos 400 por código, incluido `own-origins` configurado |
| Redirección 302 + `no-store`; 404 + página acordada | `LinkApiHttpTest`, `VencimientoHttpTest` | Demo fases A/B: 302 y 404 verificados por HTTP |
| Vencimiento exacto sin renovación | `VencimientoHttpTest` (reloj controlable, límites exactos) | Demo fase B: vencimiento real con `LINK_DURATION=PT1M`, `vence_en` estable tras visitas, 404 al vencer |
| Continuidad tras reinicio | `ReinicioServicioHttpTest` | Demo fases B y C: dos reinicios reales sobre la misma base; el enlace de 60 min sigue 302 |
| Reciclaje de alias (más cortos primero) con historial | `ReciclajeAliasHttpTest`, `AliasRecyclingTest`, `AliasSequenceTest`, `RutasReservadasHttpTest` | Demo fase B: alias `7` reciclado con ambas asignaciones en el historial (`db-stats.txt`); con vencidos de 1 y 2 caracteres se reutilizó uno de 1 |
| QR antiguo -> asignación nueva | `ReciclajeAliasHttpTest` (resolución al destino vigente) | Demo fase B: el PNG `qr-7.png` sigue decodificando el mismo `shortUrl`, que ahora redirige al nuevo destino |
| QR PNG codifica el `shortUrl` | `src/test/js/qr-code.test.mjs` (UPNG+jsQR), `extensiones/tests/qr.test.mjs`, wiring en `LinkApiHttpTest` | Demo fases A/B: PNGs decodificados con UPNG+jsQR **y zbarimg**; escaneo con celular: checklist manual |
| Concurrencia sin carreras | `ConcurrenciaHttpTest`, pruebas concurrentes de `ReciclajeAliasHttpTest` y `LinkApiHttpTest` | Medición de capacidad (10 clientes): unicidad y coherencia bajo carga |
| Capacidad ≥ 95 % ≤ 1 s (1.000 vigentes, 10 clientes) | `scripts/capacidad/` + [evidencia-capacidad.md](evidencia-capacidad.md) | Re-ejecutado en esta integración: `docs/evidencia-demo/capacidad/resumen.json` — CUMPLE |
| Evolución sin tocar lo existente | `EvolucionDuracionHttpTest` + [evidencia-evolucion.md](evidencia-evolucion.md) | Los reinicios de la demo con `LINK_DURATION` distinto ejercitan el mismo punto de variación |
| Complementos Chrome/Firefox (código, permisos, QR, errores) | `extensiones/tests/` (38 pruebas: contrato, paquetes byte a byte, QR) | Instalación manual y recorrido visual: checklist manual abajo |
| Celular en la LAN escanea el QR | — (físico) | Checklist manual: escaneo del QR en pantalla y del PNG descargado |

## Verificación ejecutada en esta integración

Todo lo siguiente se ejecutó de verdad sobre el código integrado
(`git` rama `issue/11-demo-integrada`, mismo código que el merge de #9 —
no hubo cambios de producción desde la medición de referencia):

- `mvn test`: **95 pruebas, 0 fallas, 0 errores** — suites de dominio,
  casos de uso y HTTP sobre PostgreSQL real (Testcontainers).
- `node --test` en `src/test/js`: **5/5** (QR del cliente). En
  `extensiones`: **38/38**.
- `scripts/demo/ejecutar-demo.sh` (PostgreSQL `pp6-demo-pg` descartable
  en 127.0.0.1:55434, servicio con `PUBLIC_BASE_URL=http://192.168.1.7:8080`
  y `OWN_ORIGINS` con hostname + otras IPs locales): **73/73
  verificaciones OK** en tres fases — transcripción en
  `docs/evidencia-demo/salida-demo.txt`, PNGs de QR decodificados,
  `entorno.txt` y `db-stats.txt` con el historial completo.
- `scripts/capacidad/ejecutar-medicion.sh` re-ejecutado sobre el código
  integrado (`RESULTS_DIR=docs/evidencia-demo/capacidad`): **CUMPLE** —
  creación p95 114,6 ms y resolución p95 7,7 ms, 100 % ≤ 1 s en ambas,
  0 errores, invariantes en verde, medido contra la dirección LAN.
  Equivalente a la corrida de referencia de
  [evidencia-capacidad.md](evidencia-capacidad.md) (p95 112,8 / 7,5 ms):
  los cambios integrados no afectaron la medición.
- `link-duration` de entrega verificado en `application.yml`:
  `${LINK_DURATION:PT60M}` — 60 minutos; la duración corta de la demo
  fue solo argumento de arranque (`DEMO_SHORT_DURATION`), igual que el
  experimento de #10, y la fase C confirmó la restitución.

## Límites reales declarados

- **Medición same-host**: los números de capacidad se tomaron contra la
  dirección LAN del servicio pero sin atravesar el enlace WiFi de un
  celular; son una cota inferior de latencia de red. El escaneo físico
  es checklist manual.
- **QR verificado por decodificación**, no por una cámara real: el PNG
  es byte a byte el que descarga el usuario (mismo `QrPng.encode`), y lo
  decodifican UPNG+jsQR y zbarimg — la misma operación que hace un
  lector de celular, sin el celular.
- **Firefox temporal**: la carga manual (`about:debugging`) se pierde al
  reiniciar el navegador; permanente requeriría firma AMO o edición
  Developer (fuera de alcance, ver
  [extensiones/README.md](../extensiones/README.md)).
- **HTTP sin TLS** en la demo LAN: decisión acordada (Q16).
- **Creación serializada** por diseño (`FOR UPDATE` en el generador):
  margen medido ~8× sobre el objetivo de 1 s con 10 clientes; escalarla
  es un cambio interno documentado, no un cambio de contrato.
- **Scope-outs de la etapa** (sin implementar, no son pendientes):
  cuentas/autorización, consola de gestión, estadísticas de visitas,
  alias personalizados, deduplicación/idempotencia, purga del historial,
  TLS y despliegue público. Los cambios representativos se analizaron en
  [evidencia-evolucion.md](evidencia-evolucion.md).
- **Fecha de la evidencia**: 2026-10-05, host `arcanetako` (Omarchy,
  kernel 7.2.5-3, OpenJDK 17.0.20.1, Maven 3.9.16, Node 26.7.0,
  PostgreSQL 16.4 en contenedor) — ver `docs/evidencia-demo/entorno.txt`.

## Procedimiento de demo

### A. Recorrido automatizado (ya ejecutado; reproducible)

```bash
scripts/demo/ejecutar-demo.sh
```

Levanta PostgreSQL descartable y el servicio con la configuración LAN,
y corre `recorrido-demo.mjs` en tres fases con dos reinicios reales:

1. **Fase A** (`LINK_DURATION=PT60M`, entrega): web servida, creación
   con resultado completo, QR PNG generado con el módulo real de la web
   y decodificado (UPNG+jsQR y zbarimg), redirección 302 verbatim,
   destinos LAN/credenciales/parámetros/fragmento/8.192 aceptados,
   todos los rechazos del contrato (incluidos los orígenes propios
   equivalentes), 404 de alias desconocido con la página acordada, y
   verificación de que ningún rechazo consumió alias ni avance del
   generador.
2. **Fase B** (reinicio con `LINK_DURATION=PT1M`, solo para observar el
   vencimiento en tiempo real — mismo mecanismo del experimento de #10):
   continuidad del enlace de la fase A tras reinicio, vencimiento real
   sin renovación por visitas, reciclaje del alias vencido con historial
   conservado, QR viejo resolviendo a la asignación nueva, y preferencia
   por alias más cortos aun habiendo un vencido de dos caracteres.
3. **Fase C** (reinicio a `PT60M`): continuidad y restitución de la
   duración de entrega.

Artefactos: `docs/evidencia-demo/` (`salida-demo.txt`, `qr-*.png`,
`estado.json`, `app-fase-*.log`, `entorno.txt`, `db-stats.txt`,
`capacidad/`). Código de salida 0 = todo en verde.

### B. Entorno manual (equivalente al script, paso a paso)

```bash
docker compose up -d                                # PostgreSQL 16 (5432)
PUBLIC_BASE_URL=http://<ip-lan>:8080 \
OWN_ORIGINS="http://<hostname>:8080" \
  mvn spring-boot:run                               # API + web
```

Obtener `<ip-lan>` con `ip -4 -o addr show scope global` (primera
interfaz que no sea loopback/docker/túnel). La web queda en
`http://<ip-lan>:8080`. Para el vencimiento en vivo agregar
`LINK_DURATION=PT2M` (solo afecta lo creado desde ese arranque).

Si el puerto 5432 del host ya está ocupado por otro PostgreSQL (en este
equipo lo usa un servicio ajeno), el compose admite `DB_PORT=<libre>`
para publicar en otro puerto y el servicio toma el mismo `DB_PORT` para
conectarse. El script automatizado ya evita esto con su contenedor
propio en 55434.

### C. Checklist manual de demo (pendiente-humano)

Cada ítem que requiere persona física, con qué registrar como evidencia.
Prerequisito: servicio levantado con `PUBLIC_BASE_URL` LAN (A o B) y un
celular en la misma red.

| # | Paso | Esperado | Evidencia a registrar |
| --- | --- | --- | --- |
| 1 | Web: abrir `http://<ip-lan>:8080` en el navegador de la computadora | Formulario visible | Captura de la página |
| 2 | Web: acortar un destino válido | Enlace LAN, vencimiento local, aviso de reutilización y QR visibles | Captura del resultado + alias |
| 3 | Web: «Descargar QR como PNG» | `qr-<alias>.png` válido en Descargas | Archivo descargado |
| 4 | Celular: escanear el QR en pantalla (o el PNG bajado) | Abre `http://<ip-lan>:8080/<alias>` y redirige al destino | Foto/captura del celular llegando al destino |
| 5 | Web: intentar acortar `http://ejem plo.com` y `http://localhost:8080/x` | Error visible legible, sin QR ni resultado nuevo | Captura del mensaje |
| 6 | Chrome: `chrome://extensions` → modo desarrollador → cargar `extensiones/chrome/` | Tarjeta «Acortador de enlaces (demo LAN)» | Captura de la extensión instalada |
| 7 | Chrome: en el popup, «Dirección de la API» → `http://<ip-lan>:8080` → Guardar | Configuración persistida | — |
| 8 | Chrome: en una pestaña `https://` cualquiera, clic en el ícono | Enlace, vencimiento, aviso y QR del destino de la pestaña | Captura del popup + alias |
| 9 | Chrome: «Descargar QR como PNG» | `qr-<alias>.png` en Descargas | Archivo |
| 10 | Firefox: `about:debugging` → Este Firefox → cargar `extensiones/firefox/manifest.json` | Complemento temporal instalado | Captura |
| 11 | Firefox: repetir pasos 7–9 | Mismo resultado que en Chrome | Captura del popup |
| 12 | Celular: escanear el QR del popup (Chrome y/o Firefox) | Alcanza el destino vía el acortador | Captura del celular |
| 13 | Abrir el `shortUrl` de cualquier cliente en otra pestaña | HTTP 302 al destino | — |
| 14 | Con servicio apagado, clic en el ícono del complemento | Error `NETWORK_ERROR` visible (nunca un falso éxito) | Captura del error |
| 15 | En `chrome://` o `about:` abrir el popup | `TAB_NOT_SHORTENABLE`, sin llamada a la API | Captura |
| 16 | Complementos: «Acortar de nuevo» | Nueva asignación independiente (comportamiento del contrato) | Alias distinto o mismo reciclado con nuevo `expiresAt` |

Registro sugerido: capturas en `docs/evidencia-demo/manual/` con nombre
`paso-<n>-<descripcion>.png` y una línea por paso en un `manual.md`
(OK / observación). Los pasos 5, 13–16 también pueden ejecutarse con la
fase A del script automatizado como referencia de lo esperado.

## Qué NO quedó demostrado (honestidad de la evidencia)

- Ningún humano hizo clic en un navegador real en esta ejecución: la
  evidencia visual de web y complementos es código servido + contrato +
  procedimiento, no pantallas observadas. Checklist C pendiente.
- Ningún celular real escaneó un QR: la decodificación cubre el
  contenido del código; el salto WiFi celular→servicio solo se verificó
  como alcanzabilidad HTTP de la dirección LAN desde el mismo host.
- La instalación de complementos no se ejecutó en navegadores reales:
  los paquetes se verificaron byte a byte y por contrato, pero el
  recorrido `chrome://extensions`/`about:debugging` es del checklist.
