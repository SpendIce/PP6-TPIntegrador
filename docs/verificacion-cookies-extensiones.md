# Verificación manual: ¿la extensión recibe la sesión de la web? (issue #26)

Antes de implementar el login en las extensiones (#27) hay que saber, en
navegadores reales, si el popup puede reutilizar la cookie de sesión de la web
del mismo navegador y hacer un `POST` autenticado con CSRF. Si falla en alguno
de los dos, la extensión se trata como otro dispositivo: se vincula con su
propio QR y usa un token `Authorization: Bearer` (ADR 0007).

Esta prueba no se puede automatizar: requiere instalar los complementos a mano
y mirar la consola del popup. Los resultados se registran en
[ADR 0007](adr/0007-login-google-y-sesiones-persistidas.md).

## Preparación (una sola vez)

1. Servicio publicado y con el login de la etapa 2 andando (issues #19 y #28):
   `PUBLIC_BASE_URL` apunta a la URL de Tailscale Funnel.
2. Chrome y Firefox instalados en la misma máquina.
3. En cada navegador, iniciar sesión en la web (`/login`) con la misma cuenta de
   Google. Esa es la cookie que se está probando.

## Chrome (Manifest V3)

1. Abrir `chrome://extensions`, activar **Developer mode**.
2. **Load unpacked** → elegir `extensiones/chrome`.
3. Abrir el popup del complemento y, en **Dirección de la API**, poner la URL
   pública: `https://<máquina>.<tailnet>.ts.net`. Guardar.
4. Clic derecho sobre el popup → **Inspect** para abrir su consola.
5. Comprobar que la cookie de sesión llega desde el popup:

   ```js
   await fetch('https://<máquina>.<tailnet>.ts.net/api/session', { credentials: 'include' })
     .then(async (r) => ({ status: r.status, body: await r.text() }))
   ```

   - **Esperado:** `200` con `{email, namespace, tipo}` y `tipo: "principal"`.
   - Si responde `200` con el usuario anónimo, la cookie no llegó.

6. Comprobar el `POST` autenticado con CSRF (el endpoint y el nombre del campo
   son los que declare `openapi.yaml` en el issue #19):

   ```js
   const base = 'https://<máquina>.<tailnet>.ts.net';
   const token = await fetch(`${base}/api/csrf`, { credentials: 'include' })
     .then((r) => r.json());
   await fetch(`${base}/api/links`, {
     method: 'POST',
     credentials: 'include',
     headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': token.token },
     body: JSON.stringify({ url: 'https://example.com', aliasPersonalizado: 'prueba-chrome' }),
   }).then(async (r) => ({ status: r.status, body: await r.text() }));
   ```

   - **Esperado:** `201` con un enlace personal bajo el namespace del usuario.
   - Anotar el estado y el cuerpo exactos si falla.

7. Repetir el `POST` sin el token CSRF: debe rechazarse (`403`, formato
   `{error, message}`). Si pasa, el CSRF no está protegiendo el endpoint.

## Firefox (Manifest V2)

1. Abrir `about:debugging#/runtime/this-firefox`.
2. **Load Temporary Add-on** → elegir `extensiones/firefox/manifest.json`.
3. Repetir los pasos 3 a 7 de Chrome, con la misma URL pública.
4. Anotar si la cookie viaja distinto por la **protección total de cookies**
   (particionamiento por sitio). Prueba adicional si falla: abrir la web y el
   popup en la misma pestaña/pestaña activa, y revisar en la consola del popup
   `document.cookie` y el estado de `fetch(..., { credentials: 'include' })`.

## Registro del resultado

Para cada navegador, anotar:

| Dato | Chrome | Firefox |
| --- | --- | --- |
| ¿La cookie llega al popup? (`GET /api/session`) | | |
| ¿El `POST` con CSRF crea el enlace? | | |
| Si falla: atributo o permiso probado (SameSite, host permissions, cookies particionadas) | | |

Con eso, agregar a `docs/adr/0007-login-google-y-sesiones-persistidas.md`:

```markdown
## Resultado de la prueba de cookies en extensiones

Medido el <fecha> con <versión de Chrome> y <versión de Firefox>, extensiones
cargadas a mano desde `extensiones/`.

- Chrome MV3: <resultado de GET /api/session y del POST con CSRF>.
- Firefox MV2: <resultado de GET /api/session y del POST con CSRF>.
- Vía elegida: <reutilizar la cookie de la web | plan B: QR + Bearer>.
```

Y cerrar el issue #26 con un comentario que resuma la tabla.
