# Despliegue público y Google real — #28

Verificado el 2026-11-02. Proyecto de Google Cloud: `pp6-acortador`.

## Resultado

- Tailscale conectado, HTTPS y Funnel habilitados para
  `https://arcanetako.tail631d5e.ts.net`.
- Funnel publica `http://127.0.0.1:8081`; la página principal responde 200.
- Cliente OAuth de tipo web, con ese origen y callback
  `https://arcanetako.tail631d5e.ts.net/login/oauth2/code/google`.
- Verificación HTTP: el inicio del login responde 302 hacia
  `accounts.google.com`, con el callback HTTPS correcto y los únicos scopes
  `openid email profile`.
- El usuario confirmó un login real completo con su cuenta personal, distinta
  de la cuenta de facultad que creó el proyecto, sin registrarla como usuario
  de prueba. La web mostró la sesión y la elección de namespace.
- Público externo y estado «app publicada» confirmados por el usuario después
  de completar la información de marca. El acceso anterior ocurrió mientras
  la app todavía estaba en Prueba: los scopes básicos de identidad tienen una
  excepción a la lista de usuarios de prueba de Google.
- Página principal, privacidad y condiciones verificadas por HTTPS con 200.
  La página principal enlaza ambas páginas informativas.
- Las credenciales se importaron del JSON de Google al `.env` local,
  ignorado por Git y con permisos 0600. No se publican credenciales ni el JSON.

## Entorno de esta máquina

La aplicación real se ejecuta en la ventana tmux `stinter:pp6-google-app`,
con `./gradlew --no-daemon --console=plain bootRun`, y registra salida en
`/tmp/pp6-google-app.log`. El `.env` local tiene `SERVER_PORT=8081`,
`PUBLIC_BASE_URL=https://arcanetako.tail631d5e.ts.net` y `DB_PORT=55432`.

PostgreSQL 16.4 usa el proyecto Compose `pp6`, contenedor
`acortador-postgres`, volumen persistente `pp6_postgres-data` y publicación
solo en `127.0.0.1:55432`. Para volver a levantar esa misma base:

```bash
DB_PORT=127.0.0.1:55432 docker compose -p pp6 up -d
./gradlew --no-daemon --console=plain bootRun
```

La variable de puerto con dirección de loopback se usa solo para Compose:
en `.env`, `DB_PORT` debe seguir siendo `55432` para la conexión JDBC.
El servicio simulado de #26 en 8080 y su base efímera en 55436 son
independientes. `coco-postgres` en 5432 pertenece a otro proyecto.

## Pendientes de cierre

- Confirmar que el docente puede entrar con su cuenta, especialmente si es
  Google Workspace. Esa prueba también aporta evidencia de acceso de una
  persona ajena al equipo.
- Integrar las páginas informativas y esta evidencia en main. Durante esta
  preparación se copiaron los HTML al directorio de recursos de la instancia
  activa; para conservarlos después de regenerar recursos hace falta integrar
  los archivos fuente.
- El recorrido completo con celular y extensiones es #29 y espera #27.

## Referencias

- [Excepción para permisos básicos en estado Prueba](https://support.google.com/cloud/answer/15549945?hl=en).
- [Información de marca de OAuth](https://support.google.com/cloud/answer/15549049?hl=en).
