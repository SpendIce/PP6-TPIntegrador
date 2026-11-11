# Verificación manual de la etapa 2 — issue #29

Fecha: 2026-11-08. Servicio: `https://arcanetako.tail631d5e.ts.net`
(publicado por Tailscale Funnel sobre `127.0.0.1:8081`, `SERVER_PORT=8081`,
`NAMESPACE_GRACE_PERIOD=PT2M`, `LINK_DURATION=PT40M`). PostgreSQL 16.4
descartable `acortador-postgres` en `127.0.0.1:55432` (proyecto Compose
`pp6`). Capturas en este directorio como `paso-<n>-<desc>.png`.

Recorrido de `docs/requisitos-etapa-2.md`, sección «Verificación», con la
cuenta personal de Google en el celular (sesión principal), Chrome en la
computadora (sesión vinculada por QR), Firefox (sesión principal propia) y
una segunda cuenta de Google en ventana de incógnito. Extensiones
instaladas con la dirección de la API apuntada a la URL de Funnel.

| # | Paso | Resultado | Observación |
| --- | --- | --- | --- |
| 1 | Incógnito, sin sesión: acortar un enlace y abrir el `shortUrl` | ok | redirige al destino |
| 2 | Celular: «Entrar con Google» con la cuenta personal → «Elegí tu namespace» → `juan` | ok | ver hallazgo 1 |
| 3 | Chrome: QR de «Vinculá este dispositivo» escaneado con la cámara del celular → confirmación → sesión vinculada | ok | ver hallazgo 2 |
| 4 | Chrome: enlace personal `123` → `…/juan/123` redirige; QR del enlace escaneado con el celular | ok | |
| 5 | Incógnito con la segunda cuenta: intentar elegir `juan` | ok | rechazado por ya tomado |
| 6 | Sesión de `juan`: cambiar a `juani` → `/juani/123` redirige directo; `/juan/123` muestra el aviso con cuenta regresiva y la nueva dirección, y redirige solo | ok | |
| 7 | Durante la gracia: la segunda cuenta no puede tomar `juan`; «Mi namespace» lista `juan` en «Namespaces anteriores» con su tiempo restante y el botón «Volver a este» | ok | botón verificado sin usarlo: volver a `juan` habría impedido el 404 del paso 8 |
| 8 | Terminada la gracia (`PT2M`): `/juan/123` responde 404; la segunda cuenta elige `juan` | ok | 404 verificado tras el fin de la gracia |
| 9 | Extensión Chrome (sesión vinculada): muestra email y namespace, crea enlace personal; extensión Firefox (sesión propia): igual | ok | |
| 10 | Celular: «Cerrar sesiones vinculadas» → Chrome queda sin sesión; Firefox conserva la suya; sin sesión, acortar sigue funcionando | ok | ver hallazgo 3 |

## Hallazgos

1. **Botones del encabezado cortados en pantalla angosta (defecto,
   corregido).** En el celular, email + «Cerrar sesiones vinculadas» +
   «Cerrar sesión» desbordaban el ancho: `#cuenta` es un flex sin wrap y
   los botones llevan `white-space: nowrap`. Corrección: `flex-wrap: wrap`
   en `#cuenta` (`styles.css`). Verificado servido por Funnel tras
   reiniciar la app.
2. **Escaneo del QR de vinculación (observación, acuerdo vigente).** El
   operador esperaba un botón de escaneo dentro de la web y casi reporta
   que la vinculación no funcionaba hasta usar la cámara del celular. La
   spec declara «Escáner de QR dentro de la web» fuera de alcance: se
   aclaró el texto de la sección a «Escaneá el QR con la cámara del
   celular…». Queda como UX conocida, no como defecto.
3. **Firefox no se deslogueó al cerrar sesiones vinculadas (verificado
   correcto).** Firefox tenía sesión **principal** (login directo con
   Google, confirmado por el operador) y el botón solo cierra las
   vinculadas («desloguea los dispositivos vinculados, no la sesión
   principal», acuerdo de `requisitos-etapa-2`). Estado posterior en
   `spring_session`: cero filas con `sesionTipo=vinculada`; las sesiones
   vivas del usuario son todas `principal`. El dispositivo vinculado
   (Chrome) sí quedó sin sesión.

## Estado posterior en PostgreSQL

- `spring_session`: 4 sesiones con sesión de usuario (`principal_name`):
  3 `principal` del usuario 1 (una persistida desde el 2026-11-02, las
  otras dos del día) y 1 `principal` del usuario 2. Ninguna `vinculada`:
  el cierre del paso 10 eliminó todas.
- `nombre_namespace`: `juani` del usuario 1 (vigente, sin gracia) y `juan`
  del usuario 1 con `grace_ends_at` ya vencido. La fila del namespace
  anterior en gracia se conserva tras vencer (estado perezoso): el nombre
  queda libre para que otro usuario lo tome, y al tomarlo la fila pasa al
  nuevo usuario.
