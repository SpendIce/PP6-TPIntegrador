---
status: accepted
---

# Login con Google por OIDC, sesiones en PostgreSQL y vinculación por QR

La etapa 2 agrega usuarios: se inicia sesión con Google y los dispositivos no confiables se vinculan escaneando un QR desde una sesión principal, al estilo de WhatsApp. Se acuerda usar el cliente OIDC de Spring Security con Google como único proveedor y guardar las sesiones en PostgreSQL con Spring Session JDBC. La vinculación por QR es un mecanismo propio: el dispositivo que espera muestra un código de un solo uso que vence en 60 segundos y consulta su estado cada 2 segundos; al aprobarlo desde una sesión principal, recibe una sesión vinculada.

Se prefirieron las sesiones persistidas frente a mantenerlas en memoria porque la etapa 1 ya exigía que el estado sobreviva a los reinicios: con sesiones en memoria, cada reinicio obligaría a volver a iniciar sesión con Google y a volver a escanear el QR en cada dispositivo. Para avisar si la vinculación fue aprobada se eligió la consulta periódica frente a WebSockets o SSE, porque no requiere infraestructura nueva y alcanza para una espera de segundos.

## Consecuencias

- Google exige URIs de redirección HTTPS y no admite IPs directas como host (salvo loopback). La demo deja de servirse en `http://<ip-lan>` y se publica con una URL HTTPS estable de Tailscale Funnel, que también pasa a ser `PUBLIC_BASE_URL`. Un túnel rápido de cloudflared cambia de URL en cada ejecución y el dominio gratuito de ngrok interpone una página de advertencia en los navegadores; GitHub Pages solo sirve contenido estático y no puede recibir la vuelta del login.
- El cliente se publica «En producción» pidiendo solo `openid`, `email` y `profile`, para que cualquier cuenta pública de Google pueda entrar sin verificación de la app ni lista de usuarios de prueba. Una cuenta de Google Workspace puede quedar bloqueada por las políticas de su organización.
- Con sesiones por cookie, Spring Security exige protección CSRF en los POST: la web y las extensiones deben obtener y enviar el token.
- Las extensiones reutilizan la cookie de sesión de la web del mismo navegador. No se encontró documentación que garantice que Chrome y Firefox envíen esa cookie desde el popup, así que se prueba antes de implementar. Si falla en alguno, la extensión se trata como otro dispositivo: se vincula por QR y recibe un token propio que la API acepta como `Authorization: Bearer`.
- Las suites HTTP inician sesión contra un proveedor OIDC simulado (`mock-oauth2-server`) en Testcontainers, con el flujo real de redirecciones, validación del token y sesión persistida. Se descartaron los helpers de MockMvc (`oidcLogin()`), que se saltean la vuelta del proveedor, la creación del usuario y la sesión real, y agregarían un segundo estilo de prueba al seam único del ADR 0006. El login con Google real se verifica solo en la prueba final manual.
- Las tablas de Spring Session y las nuevas del modelo también deben traducirse a `db/hsqldb/schema.sql` (ADR 0005).

Acuerdos: respuestas Q2, Q3, Q4, Q13, Q14, Q16, R3, R3', R5, R6, R8', R9 y R10 de la entrevista de la etapa 2 iniciada el 2026-10-26; ver [requisitos de la etapa 2](../requisitos-etapa-2.md).
