# Verificación manual de la demo — issue #12

Fecha: 2026-10-05. Servicio: `http://192.168.1.7:8080`
(`PUBLIC_BASE_URL` LAN, `OWN_ORIGINS=http://arcanetako:8080`,
`LINK_DURATION=PT60M`). PostgreSQL descartable `pp6-manual-pg` en
127.0.0.1:55432. Capturas en este directorio como `paso-<n>-<desc>.png`.

| # | Paso | Resultado | Observación |
| --- | --- | --- | --- |
| 1 | Web: abrir `http://192.168.1.7:8080` | ok | |
| 2 | Web: acortar destino válido (anotar alias) | ok | alias: http://192.168.1.7:8080/1 |
| 3 | Web: descargar QR PNG (`qr-<alias>.png`) | ok | |
| 4 | Celular: escanear QR, llega al destino vía LAN | ok | |
| 5 | Web: errores visibles (`http://ejem plo.com`, `http://localhost:8080/x`) | ok | |
| 6 | Chrome: `chrome://extensions` → cargar `extensiones/chrome/` | ok | |
| 7 | Chrome: configurar «Dirección de la API» | ok | |
| 8 | Chrome: acortar pestaña `https://` (anotar alias) | ok | |
| 9 | Chrome: descargar QR PNG | ok | |
| 10 | Firefox: `about:debugging` → cargar `extensiones/firefox/manifest.json` | ok | |
| 11 | Firefox: repetir pasos 7–9 | ok | |
| 12 | Celular: escanear QR del popup | ok | |
| 13 | `shortUrl` en otra pestaña redirige 302 | ok | |
| 14 | Servicio apagado → popup muestra `NETWORK_ERROR` | ok | captura `paso-14-network-error.png` |
| 15 | Popup en `chrome://`/`about:` → `TAB_NOT_SHORTENABLE` | ok | |
| 16 | «Acortar de nuevo» → asignación independiente | ok | |

Verificación adicional del AC (issue #12): el alias `/1` ya vencido
muestra la página «Este enlace no existe o venció» — captura
`paso-13b-404-pagina.png`.
