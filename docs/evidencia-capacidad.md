# Evidencia de capacidad y tiempo de respuesta (issue #9)

Ejecución de la «Prueba de capacidad» definida en
[arquitectura y validación](arquitectura-y-validacion.md#procedimiento-para-medir-respuesta)
contra los criterios de la especificación padre (issue #1, §«Capacidad y
respuesta» y §«Testing Decisions → Prueba de capacidad»):

> Con 1.000 asignaciones vigentes y 10 clientes simultáneos, al menos el
> 95 % de las solicitudes de cada operación —creación y resolución,
> evaluadas por separado— responde en un segundo o menos. La medición
> termina al recibir la respuesta del acortador; la resolución se mide
> sin seguir la redirección. Un error inesperado no cuenta como éxito.

**Resultado de la corrida de referencia: CUMPLE** — 100 % de ambas
operaciones por debajo de un segundo, con cero errores y las invariantes
verificadas, midiendo contra la dirección LAN del servicio.

## Procedimiento reproducible

La prueba completa quedó automatizada en `scripts/capacidad/`:

```bash
scripts/capacidad/ejecutar-medicion.sh
```

`ejecutar-medicion.sh` orquesta el ciclo completo desde la raíz del repo:

1. Levanta un PostgreSQL `postgres:16.4-alpine` propio y descartable
   (`docker run --rm`, contenedor `pp6-capacidad-pg`) publicado en
   `127.0.0.1:55433`. No reutiliza el `acortador-postgres` del
   docker-compose de desarrollo ni ningún servicio que ya ocupe 5432.
2. Arranca el servicio con `mvn spring-boot:run` (procedimiento de
   ejecución documentado) con `DB_PORT=55433`, `SERVER_PORT=8080` y
   `PUBLIC_BASE_URL=http://<IP-LAN>:8080`, donde la IP LAN se detecta de
   la primera interfaz global que no sea loopback, puente docker ni
   túnel (sobrescribible con `LAN_IP`).
3. Espera el servicio (`GET /` hasta 200) y comprueba su alcanzabilidad
   por la dirección LAN configurada; si la interfaz no respondiera, cae
   a loopback y lo registra en `entorno.txt`.
4. Ejecuta `medir-capacidad.mjs` (Node ≥ 18, solo módulos estándar, sin
   herramientas externas) y guarda los artefactos en `RESULTS_DIR`.
5. Captura `entorno.txt` y `db-stats.txt`, y apaga el servicio (SIGTERM
   al grupo de proceso, apagado graceful) y el contenedor.

`medir-capacidad.mjs` realiza las fases:

| Fase | Qué hace | Cuenta para el criterio |
| --- | --- | --- |
| Semilla | 1.000 `POST /api/links` con 10 clientes; cada creación exitosa alimenta el mapa alias → destino. | No |
| Calentamiento | 200 creaciones + 200 resoluciones. | No (quedan en el CSV con `phase=warmup`) |
| Creación | 1.000 `POST /api/links`, 10 clientes en modelo cerrado (cada cliente emite la siguiente al completar la anterior). | Sí |
| Resolución | 1.000 `GET /{alias}` sobre alias vigentes elegidos al azar del conjunto conocido, **sin seguir la redirección**: el cliente `node:http` nunca la sigue y se lee `Location` para verificarla. | Sí |
| Verificación | Re-resuelve una muestra de 300 alias; pide 3 alias jamás emitidos (deben dar 404 con la página acordada); una creación adicional por encima del volumen de referencia (debe dar 201). | Invariantes |

Decisiones del procedimiento:

- **Semillas por la API pública**, no por INSERT: producen el mismo
  estado que el uso real (misma transacción, mismo avance persistido de
  `generador_alias`). Un INSERT directo tendría que replicar
  `AliasSequence` (conteo base-58, longitudes, reservas) y fijar
  `proximo_indice` a mano: estado distinto al real y frágil.
- **Latencia** medida con `performance.now()` entre el envío de la
  solicitud y la recepción completa de la respuesta (cabeceras y cuerpo).
  Percentiles por rango más cercano.
- **Criterio estricto**: `ok_within_1s / requests ≥ 95 %` por operación,
  donde exitosa exige el estado esperado (201 / 302) y la verificación
  propia (`shortUrl = <public>/<alias>`; `Location` = destino
  registrado). Cero errores inesperados: una respuesta errónea rápida no
  suma.
- **Cliente y servidor en el mismo host**: la medición va contra la
  dirección LAN del servicio (`http://192.168.1.7:8080`) y confirma su
  alcanzabilidad por esa interfaz, pero no atraviesa el enlace de radio
  de un celular — esa demo es el issue #11. Los números de abajo son
  por tanto una cota inferior de latencia de red, no una extrapolación
  WiFi.

## Entorno de la corrida (2026-10-05, `docs/evidencia-capacidad/entorno.txt`)

| Elemento | Valor |
| --- | --- |
| Equipo | `arcanetako`, Omarchy (Arch), kernel Linux 7.2.5-3-omarchy x86_64 |
| CPU | 11th Gen Intel Core i7-1180G7, 8 núcleos |
| Memoria | 15 Gi total, ~7 Gi disponible al medir |
| Java / Maven | OpenJDK 17.0.20.1 / Maven 3.9.16 |
| Servicio | `mvn spring-boot:run`, Spring Boot 3.5.16, Tomcat y HikariCP con valores por defecto, `LINK_DURATION=PT60M` |
| PostgreSQL | `postgres:16.4-alpine` (server 16.4) en contenedor propio `127.0.0.1:55433` |
| Dirección pública | `http://192.168.1.7:8080` (wlp0s20f3); chequeo LAN HTTP 200; medida contra esa misma dirección |
| Cliente de carga | Node v26.7.0 en el mismo host, keep-alive, timeout 15 s |

## Resultados medidos (`resumen.json`, CSVs crudos en `docs/evidencia-capacidad/`)

### Creación — 1.000 solicitudes, 10 clientes

| n | errores | estados | mín | p50 | p95 | p99 | máx | ≤1 s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1.000 | 0 | 201 ×1000 | 19,0 ms | 89,3 ms | 112,8 ms | 147,6 ms | 175,5 ms | **100 %** |

Media 87,9 ms; ~112 creaciones/s sostenidas durante ~8,9 s. La creación
está serializada por diseño (`SELECT … FOR UPDATE` sobre la fila de
`generador_alias`, ver especificación técnica §Coordinación): la sección
serializada cuesta ~9 ms por transacción y cada solicitud espera
detrás de hasta 9 compañeras — el patrón observado (p50 ≈ 10 × 9 ms).

### Resolución — 1.000 solicitudes, 10 clientes, sin seguir redirects

| n | errores | estados | mín | p50 | p95 | p99 | máx | ≤1 s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1.000 | 0 | 302 ×1000 | 2,6 ms | 4,9 ms | 7,5 ms | 8,9 ms | 9,8 ms | **100 %** |

Dos lecturas indexadas por solicitud (referencia actual del alias y fila
de la asignación); los lectores no compiten por el bloqueo del generador.

### Invariantes bajo carga

| Invariante | Resultado |
| --- | --- |
| Unicidad de alias emitidos (2.201 creaciones totales) | OK, cero duplicados |
| Coherencia de resolución (`Location` = destino registrado, en las 1.000 medidas) | OK, cero desvíos |
| Persistencia (muestra de 300 alias re-resuelta después de la carga) | OK, 300/300 con 302 al destino correcto |
| Alias jamás emitido | 404 con «Este enlace no existe o venció» |
| Creación por encima del volumen de referencia | 201 — no hay rechazo por volumen |

### Estado de la base (`db-stats.txt`)

| Instantánea | asignaciones | alias | vigentes | `proximo_indice` | tamaño `asignacion` |
| --- | --- | --- | --- | --- | --- |
| Tras la semilla | 1.000 | 1.000 | 1.000 | 1.000 | 344 kB |
| Al final | 2.201 | 2.201 | 2.201 | 2.201 | 592 kB (`alias` 240 kB, base 8,6 MB) |

Sin asignaciones vencidas durante la corrida no hubo reciclaje: cada
creación emitió el siguiente código (los 58 de un carácter primero, el
resto de dos), coherente con `proximo_indice`. El objetivo «1.000
asignaciones vigentes» se cumplió como piso y se superó durante la propia
medición.

## Lectura de los números

- La creación serializada llega al objetivo con un margen de ~8× sobre el
  segundo: la espera por el bloqueo con 10 clientes es despreciable como
  prevé la especificación técnica. El p95 de ~113 ms queda muy por debajo
  del segundo incluso considerando que esta corrida no incluye el salto
  WiFi del celular.
- La resolución es ~18× más barata que la creación y no se degrada con
  las escrituras concurrentes, coherente con lectores READ COMMITTED sin
  bloqueos.
- No hubo incumplimientos: **no se modificó código de producción ni de
  pruebas**; el mecanismo de coordinación, el contrato y la suite de
  regresión quedan intactos.
- El procedimiento se repitió tres veces en el mismo entorno con
  resultados equivalentes (p95 de creación: 115, 108 y 113 ms; p95 de
  resolución: 6,9, 5,9 y 7,5 ms — mismo veredicto en las tres),
  lo que lo hace apto como línea base repetible para etapas siguientes.
- Dato honesto del CSV crudo: las primeras solicitudes de la fase semilla
  (fría, JVM/JIT y conexiones nuevas) muestran ~400 ms — por eso la
  semilla y el calentamiento preceden a la medición y no cuentan.

## Repetir en etapas siguientes

- Misma corrida: `scripts/capacidad/ejecutar-medicion.sh` (sobrescribe
  `RESULTS_DIR`; exportar `RESULTS_DIR=<otro>` para comparar corridas).
- Contra un servicio ya levantado:
  `node scripts/capacidad/medir-capacidad.mjs --base <url> --out <dir>`
  (opcional `PUBLIC_BASE_URL`, `DB_CONTAINER`).
- Para la demo LAN con celular (issue #11): mismo script con
  `PUBLIC_BASE_URL` en la IP LAN; el cliente puede correr en otro equipo
  de la red apuntando `--base` a esa dirección.
