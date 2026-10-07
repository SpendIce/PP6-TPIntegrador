# Acortador de enlaces — PP6 TP Integrador

Servicio de acortamiento de enlaces: API REST + web estática, monolito
modular en Java 17 / Spring Boot 3.5.16 con JPA/Hibernate sobre
PostgreSQL. Contrato en [openapi.yaml](openapi.yaml); decisiones en
[CONTEXT.md](CONTEXT.md), [docs/adr/](docs/adr/) y
[docs/especificacion-tecnica.md](docs/especificacion-tecnica.md).

**Estado de la etapa 1**: integrada y verificada. La consolidación
(estado del contrato, mapa de evidencia por requisito, límites reales y
el procedimiento de demo con su checklist manual) está en
[docs/demo-etapa-1.md](docs/demo-etapa-1.md); la evidencia cruda de la
corrida automatizada en `docs/evidencia-demo/`.

Esta guía lleva de cero a un servidor corriendo y recorre todas las
funcionalidades para probarlas: web, API, QR, vencimiento, reciclaje de
alias, historial, complementos de navegador y las suites automatizadas.

## Requisitos

| Herramienta | Versión | Para qué |
| --- | --- | --- |
| Java | 17 | Compilar y correr el servicio. Gradle no hace falta instalarlo: el repo incluye el wrapper (`gradlew`), que descarga la versión fijada solo. |
| Docker + Compose | reciente | PostgreSQL de desarrollo y Testcontainers de las pruebas. Sin Docker se puede correr igual con el perfil HSQLDB (ver abajo). |
| Node.js | ≥ 20 | Solo para las pruebas del QR del cliente (`src/test/js`) y de los complementos (`extensiones/`). No interviene en el build. |
| `zbarimg` | opcional | Decodificar los PNG del QR sin un celular. |

Los comandos asumen un shell tipo bash en Linux/macOS. En Windows usar
`gradlew.bat` en lugar de `./gradlew` y asignar las variables de entorno
según el shell (PowerShell: `$env:DB_PORT="55432"`; CMD: `set DB_PORT=55432`).

## Levantar el servidor

El servicio corre contra PostgreSQL (defecto, con Docker) o contra
HSQLDB (sin Docker, perfil `hsqldb`). Elegir una opción.

### Opción A: sin Docker (HSQLDB)

Dos terminales: una con el servidor HSQLDB y otra con el servicio.

```bash
./gradlew hsqldbServer        # terminal 1: base en localhost:9001 (queda corriendo)
./gradlew bootRun -Phsqldb    # terminal 2: el servicio en http://localhost:8080
```

En Windows: `gradlew.bat hsqldbServer` y `gradlew.bat bootRun -Phsqldb`.

La tarea `hsqldbServer` persiste la base en `hsqldb-data/` (ignorada por
git; borrarla para empezar de cero) y la apaga con `Ctrl+C`. El perfil
`hsqldb` fija el datasource `jdbc:hsqldb:hsql://localhost:9001/xdb`,
usuario `sa`, contraseña vacía (`application-hsqldb.yml`) y crea el
esquema desde `db/hsqldb/schema.sql` con `spring.sql.init` — Flyway 11
ya no soporta HSQLDB, así que en este perfil no corre Flyway ni
`ddl-auto=validate`.

### Opción B: PostgreSQL con Docker

```bash
docker compose up -d
```

Levanta `postgres:16.4-alpine` como contenedor `acortador-postgres` con
base, usuario y contraseña `acortador`, publicado en `localhost:5432` y
con volumen persistente `postgres-data`. Verificar que quedó sano:

```bash
docker compose ps          # STATUS: (healthy)
docker compose logs -f     # seguir el log; Ctrl+C solo sale del log
```

Si el puerto 5432 del host ya está ocupado por otro PostgreSQL, el
compose admite publicar en otro puerto; el servicio se conecta con la
misma variable:

```bash
DB_PORT=55432 docker compose up -d
DB_PORT=55432 ./gradlew bootRun
```

Para apagar: `docker compose down` (los datos persisten). Para borrar
la base completa: `docker compose down -v`.

Con la base arriba, el servicio:

```bash
./gradlew bootRun
```

Al arrancar, Flyway aplica las migraciones (`V1__esquema_inicial.sql`,
`V2__indice_vencimiento_asignacion.sql`) y Tomcat queda escuchando en
`http://localhost:8080`. Se apaga con `Ctrl+C` (apagado ordenado).

Comprobar que está arriba:

```bash
curl -i http://localhost:8080/     # 200, la página del acortador
```

### Levantar para una demo en red local (celular, otros equipos)

Para que el `shortUrl` y el QR sirvan desde otros dispositivos, la
dirección pública debe ser la IP del host en la LAN, no `localhost`:

```bash
ip -4 -o addr show scope global    # primera interfaz que no sea loopback/docker/túnel

IP_LAN=192.168.1.50   # <- reemplazar por la IP del paso anterior
PUBLIC_BASE_URL="http://$IP_LAN:8080" \
OWN_ORIGINS="http://$(hostname):8080" \
  ./gradlew bootRun
```

La web queda en `http://<ip-lan>:8080` para cualquier equipo de la red.
`OWN_ORIGINS` declara orígenes propios extra (hostname, otras IPs del
host) para que también se rechacen como destino; ver la tabla de
configuración abajo.

## Configuración de despliegue

| Variable | Propiedad | Defecto | Uso |
| --- | --- | --- | --- |
| `PUBLIC_BASE_URL` | `shortener.public-base-url` | `http://localhost:8080` | Dirección pública para componer `shortUrl`. Para la demo LAN usar la IP del host, p. ej. `http://192.168.1.50:8080`. |
| `OWN_ORIGINS` | `shortener.own-origins` | vacío | Orígenes propios extra (CSV, `esquema://host[:puerto]`) a rechazar como destino; se suman al origen público y a los equivalentes loopback. |
| `LINK_DURATION` | `shortener.link-duration` | `PT60M` | Duración de las nuevas asignaciones (ISO-8601). Solo aplica a lo creado desde ese arranque; lo vigente conserva su `vence_en`. |
| `SERVER_PORT` | `server.port` | `8080` | Puerto HTTP. |
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USER` `DB_PASSWORD` | `spring.datasource.*` | `localhost` `5432` `acortador` | Conexión a PostgreSQL. |

Las rutas reservadas del generador de alias se configuran con
`shortener.reserved-routes` (por defecto `api` y `error`): nunca se
emiten como alias.

## Probar las funcionalidades

Con el servidor arriba, cada sección siguiente ejercita una
funcionalidad. Todo puede hacerse a mano con `curl` y un navegador.

### Web (`GET /`)

Abrir `http://localhost:8080`. Escribir una dirección `http(s)://` en
«Dirección a acortar» y pulsar **Acortar**. La página muestra:

- el enlace acortado clicable (`http://localhost:8080/<alias>`),
- el vencimiento en hora local,
- el aviso de que el alias puede reutilizarse tras el vencimiento,
- el QR del enlace y **Descargar QR como PNG** (`qr-<alias>.png`).

Un destino inválido muestra el mensaje del contrato en el propio
formulario (sin QR ni resultado).

### API: crear enlaces (`POST /api/links`)

```bash
curl -i -X POST http://localhost:8080/api/links \
  -H 'Content-Type: application/json' \
  -d '{"destination":"https://ejemplo.com/docs/archivo?a=1&b=2#seccion"}'
```

Respuesta `201` con `Cache-Control: no-store`:

```json
{"shortUrl":"http://localhost:8080/1","alias":"1",
 "expiresAt":"2026-10-07T01:06:17.271305309Z",
 "reuseNotice":"Este alias puede reutilizarse con otro destino después del vencimiento."}
```

Cada `POST` crea una asignación independiente: repetir el mismo destino
produce otro alias y otro vencimiento (no hay deduplicación ni
idempotencia). El servidor acepta destinos inalcanzables de formato
válido: no consulta disponibilidad, autenticación ni permisos del
destino, y lo conserva verbatim (parámetros repetidos, fragmento,
credenciales `usuario:clave@`, puerto explícito, percent-encoding).

Los rechazos responden `400` con `{error, message}`; ninguno crea
asignación ni consume alias. Un ejemplo de cada código:

```bash
# EMPTY_DESTINATION
curl -s -X POST http://localhost:8080/api/links \
  -H 'Content-Type: application/json' -d '{"destination":""}'

# DESTINATION_TOO_LONG (8193 caracteres = 14 de prefijo + 8179; con 8192 se acepta)
curl -s -X POST http://localhost:8080/api/links \
  -H 'Content-Type: application/json' \
  -d "{\"destination\":\"https://e.com/$(printf 'a%.0s' {1..8179})\"}"

# MALFORMED_DESTINATION (espacio sin codificar; también % inválido o Unicode en crudo)
curl -s -X POST http://localhost:8080/api/links \
  -H 'Content-Type: application/json' -d '{"destination":"http://ejem plo.com"}'
curl -s -X POST http://localhost:8080/api/links \
  -H 'Content-Type: application/json' -d '{"destination":"https://e.com/%zz"}'
curl -s -X POST http://localhost:8080/api/links \
  -H 'Content-Type: application/json' -d '{"destination":"https://ejemplo.com/josé"}'

# UNSUPPORTED_SCHEME (solo http/https)
curl -s -X POST http://localhost:8080/api/links \
  -H 'Content-Type: application/json' -d '{"destination":"ftp://ejemplo.com/x"}'

# OWN_ORIGIN (el propio servicio y equivalentes loopback del mismo puerto)
curl -s -X POST http://localhost:8080/api/links \
  -H 'Content-Type: application/json' -d '{"destination":"http://localhost:8080/x"}'

# INVALID_REQUEST (JSON inválido, cuerpo ausente o propiedades desconocidas)
curl -s -X POST http://localhost:8080/api/links \
  -H 'Content-Type: application/json' -d '{"destino":"https://ejemplo.com"}'
```

### API: resolver enlaces (`GET /{alias}`)

```bash
curl -i http://localhost:8080/1
# HTTP 302, Location: <destino verbatim>, Cache-Control: no-store
```

Un alias desconocido o vencido responde `404` con la página «Este
enlace no existe o venció». Las visitas no renuevan el vencimiento: la
vigencia solo se evalúa en el instante de la resolución.

### Vencimiento y reciclaje de alias (observable en vivo)

La duración de entrega es 60 minutos; para ver el ciclo completo en un
minuto, reiniciar el servicio con una duración corta (afecta solo a lo
creado desde ese arranque):

```bash
LINK_DURATION=PT1M ./gradlew bootRun
```

```bash
# 1. Crear, anotar el "alias" de la respuesta y resolverlo (302)
curl -s -X POST http://localhost:8080/api/links \
  -H 'Content-Type: application/json' -d '{"destination":"https://destino-viejo.com/"}'
ALIAS=1   # <- el "alias" devuelto arriba
curl -i "http://localhost:8080/$ALIAS"        # 302 al destino

# 2. Esperar ~60 s: el mismo alias responde 404
sleep 65
curl -i "http://localhost:8080/$ALIAS"        # 404 con la página acordada

# 3. Crear otro enlace: el alias vencido se recicla y el enlace
#    anterior (o su QR) ahora conduce al destino nuevo
curl -s -X POST http://localhost:8080/api/links \
  -H 'Content-Type: application/json' -d '{"destination":"https://destino-nuevo.com/"}'
curl -i "http://localhost:8080/$ALIAS"        # 302 a destino-nuevo
```

El reciclaje prefiere los alias vencidos más cortos; sin vencidos
reutilizables, emite el siguiente código nunca usado.

### Historial de asignaciones y persistencia

La base conserva todas las asignaciones, vencidas o reasignadas:

```bash
docker exec -it acortador-postgres psql -U acortador -d acortador
```

```sql
select id, alias_codigo, destino, vence_en > now() as vigente
  from asignacion order by id;            -- historial completo
select codigo, asignacion_actual_id from alias;   -- referencia vigente
select proximo_indice from generador_alias;       -- avance del generador
```

Con el perfil `hsqldb` la consulta equivalente sale del wrapper contra el
servidor de la terminal 1:

```bash
./gradlew hsqldbSql -Psql="select id, alias_codigo, destino from asignacion order by id"
./gradlew hsqldbSql -Psql="select proximo_indice from generador_alias"
```

Tras un `Ctrl+C` y un nuevo `./gradlew bootRun` sobre la misma base,
los enlaces vigentes siguen resolviendo y el generador continúa sin
repetir alias (su avance está persistido en `generador_alias`).

### QR del enlace

Tanto la web como los complementos generan el QR en el cliente sobre el
`shortUrl` de la respuesta (no del destino). Para verificarlo sin
celular:

```bash
zbarimg ~/Descargas/qr-1.png   # el PNG descargado; decodifica al shortUrl exacto
```

El QR de un alias reciclado sigue conteniendo el mismo `shortUrl`, que
conduce a la asignación vigente: un QR viejo puede abrir un destino
nuevo.

### Complementos de navegador (Chrome y Firefox)

`extensiones/` contiene paquetes instalables manualmente: acortan la
URL de la pestaña activa con la misma API y muestran enlace,
vencimiento, aviso y QR descargable.

- Chrome: `chrome://extensions` → modo desarrollador → «Cargar
  extensión sin empaquetar» → elegir `extensiones/chrome/`.
- Firefox: `about:debugging` → «Este Firefox» → «Cargar complemento
  temporal» → elegir `extensiones/firefox/manifest.json` (la carga es
  temporal: se pierde al reiniciar el navegador).

En el popup, «Dirección de la API» acepta el origen del servicio
(`http://localhost:8080` por defecto; `192.168.x.y:8080` sin esquema se
normaliza a `http://`). Errores visibles: `NETWORK_ERROR` con el
servicio apagado, `TAB_NOT_SHORTENABLE` en páginas
`chrome://`/`about:`/`file://`, `CONFIG_INVALID`, `REQUEST_TIMEOUT` y
los rechazos `400` del contrato. Detalle completo en
[extensiones/README.md](extensiones/README.md).

### Celular en la LAN

Con el servicio levantado con `PUBLIC_BASE_URL` en la IP del host:
escanear el QR mostrado en la web o en el popup, o abrir el `shortUrl`
directamente. El celular llega al destino a través del acortador.

## Verificación automatizada

```bash
./gradlew test
```

Compila y corre las pruebas: unitarias de dominio y casos de uso, más el
recorrido HTTP completo sobre PostgreSQL real con Testcontainers
(creación, contrato de respuesta, persistencia, redirección 302/404,
rechazos del contrato y recursos estáticos de la web, incluido el QR).
No ejecutar `./gradlew build` ni `./gradlew check` como verificación de
cambios: `./gradlew test` es la evidencia acordada.

Las pruebas del QR del cliente (`src/test/js/`) y de los complementos
corren aparte con Node y no intervienen en el build de Gradle:

```bash
(cd src/test/js && node --test)    # QR web: PNG decodificado con UPNG+jsQR
(cd extensiones && node --test)    # 38 pruebas: contrato, paquetes, QR
```

El procedimiento completo del QR está en
[docs/verificacion-qr.md](docs/verificacion-qr.md).

La prueba de capacidad (1.000 asignaciones vigentes, 10 clientes
simultáneos, ≥ 95 % de cada operación en un segundo o menos) corre
aparte con `scripts/capacidad/ejecutar-medicion.sh`; procedimiento y
resultados medidos en
[docs/evidencia-capacidad.md](docs/evidencia-capacidad.md).

La demo integrada de la etapa (recorrido completo con reinicios reales:
creación, QR verificado por decodificación, errores, vencimiento,
reciclaje con historial y QR viejo a asignación nueva) corre con
`scripts/demo/ejecutar-demo.sh`; resultados y checklist manual en
[docs/demo-etapa-1.md](docs/demo-etapa-1.md).

## Problemas frecuentes

- **`docker compose up` falla por puerto ocupado**: otro PostgreSQL usa
  5432. Usar `DB_PORT=<libre>` en el compose y en el servicio.
- **No hay Docker disponible**: usar el perfil `hsqldb` (Opción A
  arriba); solo requiere Java 17.
- **El servicio no conecta a la base**: con PostgreSQL, el contenedor
  debe estar `healthy` (`docker compose ps`) y las variables `DB_*`
  deben coincidir con lo publicado; con HSQLDB, la terminal 1
  (`hsqldbServer`) debe estar corriendo en el puerto 9001.
- **El enlace/QR no abre desde otro equipo**: se generó con
  `PUBLIC_BASE_URL=http://localhost:8080`. Reiniciar con la IP LAN y
  generar un enlace nuevo: el QR descargado antes sigue codificando un
  `shortUrl` con `localhost`, que el celular resuelve contra sí mismo.
- **`OWN_ORIGIN` al acortar el propio servicio**: es el comportamiento
  del contrato; los orígenes propios (público configurado, loopback
  equivalentes y `OWN_ORIGINS`) se rechazan como destino.
- **El historial desapareció**: `docker compose down -v` borra el
  volumen (PostgreSQL) o se borró `hsqldb-data/` (HSQLDB); `down` sin
  `-v` conserva los datos.
- **Alias `api` o `error` nunca aparecen**: son rutas reservadas
  (`shortener.reserved-routes`).

## Esquema y migraciones

Flyway versiona el esquema PostgreSQL desde
`src/main/resources/db/migration/`. `V1__esquema_inicial.sql` crea
`alias`, `asignacion` (historial) y `generador_alias` (avance
persistente de la generación secuencial), con la FK compuesta que
garantiza que la referencia actual de cada alias pertenece a ese mismo
alias. `V2__indice_vencimiento_asignacion.sql` agrega el índice de
vencimiento. `ddl-auto=validate`: el esquema solo evoluciona por
migraciones.

El perfil `hsqldb` crea el mismo esquema traducido desde
`src/main/resources/db/hsqldb/schema.sql` vía `spring.sql.init`
(Flyway 11 no soporta HSQLDB): `VARCHAR(8192)` en lugar de `TEXT` y
`TIMESTAMP` en lugar de `TIMESTAMPTZ`.

## Estructura del repositorio

```text
src/main/java/...   dominio, casos de uso, HTTP y persistencia (monolito modular)
src/main/resources/ application.yml, perfil hsqldb, migraciones Flyway y web estática
src/test/java/      pruebas de dominio, casos de uso y HTTP (Testcontainers)
src/test/js/        pruebas del QR del cliente (node --test)
extensiones/        complementos Chrome/Firefox + sus pruebas (node --test)
scripts/            demo integrada y medición de capacidad
docs/               ADR, especificación técnica, evidencia y guías de agentes
build.gradle        build Gradle: dependencias, perfil hsqldb y tareas HSQLDB
gradlew, gradlew.bat, gradle/  wrapper de Gradle versionado (8.14.5); no hace falta Gradle instalado
openapi.yaml        contrato de la API
docker-compose.yml  PostgreSQL 16 de desarrollo
```
