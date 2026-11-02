# Login y enlaces personales desde las extensiones — #27

Implementación sobre la decisión de #26 / ADR 0007: se reutiliza la sesión web
del mismo navegador con `credentials: 'include'` y CSRF de doble envío. No se
implementó el plan B QR/Bearer. Manifests y permisos no cambiaron.

## Qué cambió

- `extensiones/compartido/api.js`: `getSession` (`GET /api/sesion`),
  `buildLoginUrl`, `logout` (`POST /api/logout` con `X-XSRF-TOKEN`) y
  `aliasPersonalizado` opcional en `buildCreateLinkRequest` / `createLink`.
- `extensiones/compartido/popup.{html,js,css}`: sección de cuenta (email,
  aviso sin namespace, alias opcional, cerrar sesión) y botón de
  login; sin sesión se conserva el acorte inmediato de la etapa 1.
- Copias de `chrome/` y `firefox/` regeneradas con `extensiones/sincronizar.sh`;
  el stack QR se reutiliza sin cambios.

## Automatizado

`node --test` en `extensiones/` (54 pruebas) y en `src/test/js/` (38), sin
dependencias de navegador. Cubren: sesión con usuario / `null` / respuesta
fuera de contrato / error de red; cuerpo sin alias, con alias vacío y con alias
recortado; CSRF obtenido y enviado en el POST de enlace y en el de logout;
propagación de `ALIAS_TAKEN`, `NAMESPACE_NOT_CHOSEN`, `ALIAS_INVALID`,
`NOT_AUTHENTICATED` y `CSRF_TOKEN_INVALID`; cableado de ids del popup; copias
idénticas a las fuentes.

`./gradlew --no-daemon --console=plain test` lo ejecuta el Oracle; no se
corrió en esta implementación.

## Pendiente (no bloquea #27)

La interacción visual del popup en Chrome y Firefox reales y el recorrido
HTTPS con Google real quedan en #29 (login con Google en #28). Esta evidencia
no los cubre.
