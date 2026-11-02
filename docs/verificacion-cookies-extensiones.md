# Verificación manual: ¿la extensión recibe la sesión de la web? (issue #26)

Antes de implementar el login en las extensiones (#27) hay que saber, en
navegadores reales, si el popup puede reutilizar la cookie de sesión de la web
del mismo navegador y hacer un `POST` autenticado con CSRF. Si falla en alguno
de los dos, la extensión se trata como otro dispositivo: se vincula con su
propio QR y usa un token `Authorization: Bearer` (ADR 0007).

El transporte de sesión y CSRF se puede automatizar instalando los complementos
originales en perfiles aislados y ejecutando los pedidos desde `popup.html`,
con Playwright en Google Chrome y WebDriver BiDi en Firefox. El procedimiento manual
de abajo también permite probar el perfil habitual. Los resultados se registran
en [ADR 0007](adr/0007-login-google-y-sesiones-persistidas.md).

La [medición local del 2026-11-02](evidencia-cookies-extensiones.md) pasó en
Google Chrome y Firefox. La vía elegida es reutilizar la cookie de sesión web
con CSRF. La prueba HTTPS con Google real y la interacción visual corresponden
al recorrido final de #29.

## Preparación (una sola vez)

1. Servicio con el login de la etapa 2 andando (issue #19). Se puede usar
   Google simulado en local, sin depender de #28, o Google real con
   `PUBLIC_BASE_URL` apuntando a la URL HTTPS de Tailscale Funnel.
2. Chrome y Firefox instalados en la misma máquina.
3. En cada navegador, iniciar sesión en la web (`/login`) con el mismo usuario.
   Esa es la cookie que se está probando. Elegir un namespace antes del POST.
   Con el proveedor simulado, completar `username` con `prueba-cookies` y
   `claims` con `{"email":"prueba-cookies@example.com"}`.
4. Usar siempre el mismo host: para la prueba local, `http://localhost:8080`
   tanto en la web como en el popup (no alternar con `127.0.0.1`). Registrar
   el origen probado: un resultado local no verifica el despliegue HTTPS.

## Chrome (Manifest V3)

1. Abrir `chrome://extensions`, activar **Developer mode**.
2. **Load unpacked** → elegir `extensiones/chrome`.
3. Abrir el popup del complemento y, en **Dirección de la API**, poner la URL
   del servicio: `http://localhost:8080` en local o la URL HTTPS pública. Guardar.
4. Clic derecho sobre el popup → **Inspect** para abrir su consola.
5. Comprobar que la cookie de sesión llega desde el popup:

   ```js
   await fetch('http://localhost:8080/api/sesion', { credentials: 'include' })
     .then(async (r) => ({ status: r.status, body: await r.text() }))
   ```

   - **Esperado:** `200` con `{usuario: {email, namespace, tipo, ...}}` y
     `tipo: "principal"`.
   - Si responde `200` con `{usuario: null}`, la sesión no llegó. Comprobar
     que la misma ruta en la web sí devuelve el usuario autenticado.

6. Comprobar el `POST` autenticado con CSRF. Para HTTPS, reemplazar `base`
   por el origen público también en el GET anterior:

   ```js
   const base = 'http://localhost:8080';
   const token = await fetch(`${base}/api/csrf`, { credentials: 'include' })
     .then((r) => r.json());
   await fetch(`${base}/api/links`, {
     method: 'POST',
     credentials: 'include',
     headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token.token },
     body: JSON.stringify({ destination: 'https://example.com', aliasPersonalizado: `prueba-${Date.now()}` }),
   }).then(async (r) => ({ status: r.status, body: await r.text() }));
   ```

   - **Esperado:** `201` con un enlace personal bajo el namespace del usuario.
   - Anotar el estado y el cuerpo exactos si falla.

7. Repetir el `POST` sin el token CSRF: debe rechazarse (`403`, formato
   `{error, message}` con `error: "CSRF_TOKEN_INVALID"`). Si pasa, el CSRF no
   está protegiendo el endpoint.

   ```js
   await fetch(`${base}/api/links`, {
     method: 'POST',
     credentials: 'include',
     headers: { 'Content-Type': 'application/json' },
     body: JSON.stringify({ destination: 'https://example.com', aliasPersonalizado: `sin-csrf-${Date.now()}` }),
   }).then(async (r) => ({ status: r.status, body: await r.text() }));
   ```

## Firefox (Manifest V2)

1. Abrir `about:debugging#/runtime/this-firefox`.
2. **Load Temporary Add-on** → elegir `extensiones/firefox/manifest.json`.
3. Repetir los pasos 3 a 7 de Chrome, con la misma URL pública.
4. Anotar si la cookie viaja distinto por la **protección total de cookies**
   (particionamiento por sitio). Prueba adicional si falla: abrir la web y el
   popup con la web como pestaña activa, y revisar el estado de
   `fetch(..., { credentials: 'include' })` y las cookies del pedido en la
   pestaña Network. `document.cookie` en el popup pertenece al origen de la
   extensión y no permite comprobar la cookie HttpOnly `SESSION` del servicio.

## Registro del resultado

Para cada navegador, anotar:

| Dato | Chrome | Firefox |
| --- | --- | --- |
| Versión del navegador y origen probado | | |
| ¿La sesión llega al pedido del popup? (`GET /api/sesion`) | | |
| ¿El `POST` con CSRF crea el enlace? | | |
| ¿El `POST` sin CSRF devuelve 403? | | |
| Si falla: atributo o permiso probado (SameSite, host permissions, cookies particionadas) | | |

Con eso, agregar a `docs/adr/0007-login-google-y-sesiones-persistidas.md`:

```markdown
## Resultado de la prueba de cookies en extensiones

Medido el <fecha> con <versión de Chrome> y <versión de Firefox>, extensiones
cargadas a mano desde `extensiones/`.

- Chrome MV3: <resultado de GET /api/sesion y de los POST con y sin CSRF>.
- Firefox MV2: <resultado de GET /api/sesion y de los POST con y sin CSRF>.
- Vía elegida: <reutilizar la cookie de la web | plan B: QR + Bearer>.
```

Y cerrar el issue #26 con un comentario que resuma la tabla.
