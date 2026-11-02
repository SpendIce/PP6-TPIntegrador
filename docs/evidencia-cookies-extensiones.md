# Sesión de la web desde las extensiones — #26

Medición del 2026-11-02 sobre `42a2540`, con las extensiones originales,
sin modificar manifests, permisos, CSP ni políticas de cookies.

| Comprobación | Google Chrome 156.0.8078.79, MV3 | Firefox 158.0.1, MV2 |
| --- | --- | --- |
| Login OIDC simulado en la web | Sesión principal | Sesión principal |
| `GET /api/sesion` desde `popup.html`, con `credentials: 'include'` | 200 con el mismo usuario y namespace | 200 con el mismo usuario y namespace |
| `POST /api/links` con alias personalizado y `X-XSRF-TOKEN` | 201, enlace bajo `auto-google-chrome` | 201, enlace bajo `auto-firefox` |
| Mismo POST sin encabezado CSRF | 403, `CSRF_TOKEN_INVALID` | 403, `CSRF_TOKEN_INVALID` |

## Entorno y alcance

- Origen web y API: `http://localhost:8080`.
- PostgreSQL 16.4 efímero, contenedor `pp6-cookies-pg`, puerto 55436.
- Proveedor `ghcr.io/navikt/mock-oauth2-server:6.1.2`, contenedor
  `pp6-cookies-oidc`, puerto 55526; issuer `http://localhost:55526/default`.
- Usuarios ficticios `auto-firefox@example.com` y `auto-google-chrome@example.com`.
- Navegadores en modo headless y con perfiles propios en
  `/tmp/pp6-cookies-{firefox,google-chrome}-profile`.
- Google Chrome estable: paquete oficial descargado desde
  `https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb`,
  extraído en `/tmp/pp6-google-chrome` sin instalación en el sistema.
  Playwright 1.64.1 y CDP `Extensions.loadUnpacked` cargaron la extensión
  original de `extensiones/chrome`, con `--enable-unsafe-extension-debugging`.
  Se omitió el argumento `--disable-features` predeterminado de Playwright:
  incluye `ThirdPartyStoragePartitioning`, que alteraría el entorno de la
  prueba. No se desactivó el particionamiento de almacenamiento.
- Firefox: WebDriver BiDi, instalación temporal desde `extensiones/firefox`.
  `--remote-allow-system-access` permite que la automatización navegue a la
  página del complemento; no modifica SameSite ni el particionamiento.
- Se abrió la página original `popup.html` como documento del complemento en
  una pestaña y se ejecutaron los `fetch` allí. No se probó visualmente el
  desplegable de la barra de herramientas ni se inyectaron cookies de sesión:
  estas las emitió la aplicación durante el login OIDC del navegador.

Los scripts de esta corrida están en `/tmp/pp6-bidi-probe.mjs` y
`/tmp/pp6-google-chrome-cookie-probe.mjs`; las respuestas completas, en
`/tmp/pp6-firefox-cookie-result.json` y
`/tmp/pp6-google-chrome-cookie-result.json`. Son archivos temporales de la máquina.

Hubo una corrida preliminar en Chromium 155.0.8059.84 que también dio
200/201/403, pero usaba los argumentos predeterminados de Playwright,
incluida la desactivación de `ThirdPartyStoragePartitioning`. La conclusión
se apoya en la corrida posterior de Google Chrome con esa opción retirada.

## Decisión

Reutilizar la sesión web con cookies y CSRF en las extensiones de Chrome y
Firefox. Ninguno de los dos navegadores requirió el plan B de QR con Bearer.

La prueba de transporte de #26 queda comprobada en ambos navegadores. Esto
no demuestra el origen HTTPS publicado con Google real, pendiente en #28 y
#29, ni que el perfil habitual del usuario tenga las mismas preferencias que
los perfiles aislados. La interacción visual con el popup se verifica en #29.
