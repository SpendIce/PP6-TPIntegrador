# Publicar la demo con Tailscale Funnel y Google (etapa 2)

La etapa 2 inicia sesión con Google por OIDC. Google exige una URI de
redirección HTTPS y no acepta IPs directas, así que la demo deja de servirse en
`http://<ip-lan>:8080` y pasa a una URL estable de **Tailscale Funnel**. Esa URL
es también `PUBLIC_BASE_URL` para los enlaces y los QR.

El procedimiento completo está automatizado en
[`scripts/wizard-funnel-google.sh`](../scripts/wizard-funnel-google.sh):

```bash
bash scripts/wizard-funnel-google.sh
```

El wizard abre cada consola, dice qué tocar, captura los valores y escribe el
`.env` local (ignorado por git). Se puede cortar con Ctrl-C y retomar: recuerda
lo ya guardado.

## Pasos, en orden

### 1. Tailscale: URL pública HTTPS

1. `tailscale up` en la máquina que va a hospedar el servicio.
2. En la consola de administración, **DNS → HTTPS Certificates → Enable HTTPS**
   (una sola vez por tailnet).
3. En **Access controls**, habilitar Funnel para el nodo
   (`nodeAttrs` con `funnel`). Sin esto, `tailscale funnel` responde con un
   error de permisos.
4. Publicar el puerto de la app: `tailscale funnel --bg 8080`.
5. Comprobar con `tailscale funnel status`: debe mostrar
   `https://<máquina>.<tailnet>.ts.net → 127.0.0.1:8080`.

La URL resultante es `https://<máquina>.<tailnet>.ts.net`.

### 2. Google Cloud: consentimiento

1. Crear un proyecto en <https://console.cloud.google.com/projectcreate>.
2. En **Google Auth Platform** (o *APIs & Services → OAuth consent screen* en
   consolas viejas): User type **External**.
3. Publicar la app **In production**. Así cualquier cuenta pública de Google
   entra sin figurar como usuario de prueba.
4. Data access: solo `openid`, `email` y `profile`. Los tres son no sensibles:
   no requieren verificación de Google.

### 3. Google Cloud: cliente OAuth web

1. **Create credentials → OAuth client ID → Web application**.
2. Authorized JavaScript origins: `https://<máquina>.<tailnet>.ts.net`.
3. Authorized redirect URI:
   `https://<máquina>.<tailnet>.ts.net/login/oauth2/code/google`.
   Es el camino por defecto del cliente OAuth2 de Spring Security; si cambia la
   implementación del login, esta URI y el wizard tienen que cambiar juntos.
4. Copiar el **Client ID** y el **Client secret**.

### 4. `.env` local

El archivo es formato properties: **sin comillas ni `export`**.

```properties
PUBLIC_BASE_URL=https://<máquina>.<tailnet>.ts.net
GOOGLE_CLIENT_ID=<client-id>.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=<client-secret>
# Opcional, para ver el fin del período de gracia en la demo:
# NAMESPACE_GRACE_PERIOD=PT2M
```

`OWN_ORIGINS` no hace falta: `PUBLIC_BASE_URL` ya se suma a los orígenes propios
que se rechazan como destino.

### 5. Verificación

- `curl -I https://<máquina>.<tailnet>.ts.net/` responde 200 con la app
  corriendo (`./gradlew bootRun`).
- Con el login implementado, una cuenta de Google **que no sea del equipo**
  entra sin estar en una lista de usuarios de prueba.
- El docente prueba su cuenta temprano: una cuenta de Google Workspace puede
  quedar bloqueada por la política de su organización.

## Cuando algo no responde

| Síntoma | Causa probable |
| --- | --- |
| `tailscale funnel` falla con permisos | Funnel no habilitado para el nodo en la política de la tailnet |
| Funnel responde, pero el navegador da error de certificado | HTTPS no habilitado en la tailnet |
| Funnel responde 502 | La app no está escuchando en `127.0.0.1:8080` |
| Google responde `redirect_uri_mismatch` | La URI registrada no coincide exacto con la que usa la app |
| Google responde «acceso bloqueado» | La app quedó en *Testing* o el alcance pedido no está declarado |
