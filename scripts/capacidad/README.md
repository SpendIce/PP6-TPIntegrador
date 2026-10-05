# Prueba de capacidad y tiempo de respuesta

Implementa la «Prueba de capacidad» de la especificación padre (issue #1,
§Capacidad y respuesta y §Testing Decisions) para el issue #9:

> 1.000 asignaciones vigentes, 10 clientes simultáneos, al menos el 95 %
> de las solicitudes de cada operación (creación y resolución, evaluadas
> por separado) respondidas en un segundo o menos. La medición termina al
> recibir la respuesta del acortador: la resolución no sigue redirects.

## Ejecución completa (recomendada)

```bash
scripts/capacidad/ejecutar-medicion.sh
```

Desde la raíz del repo, el script hace todo el ciclo: PostgreSQL efímero
(`--rm`, contenedor `pp6-capacidad-pg`, host `127.0.0.1:55433` para no
pisar el `acortador-postgres` de desarrollo ni otros servicios en 5432),
servicio con `mvn spring-boot:run` y `PUBLIC_BASE_URL` apuntando a la IP
LAN detectada, chequeo de alcanzabilidad LAN, medición, captura de
entorno y estadísticas de la base, y apagado ordenado.

Variables de entorno opcionales: `CAP_DB_PORT` (55433), `APP_PORT`
(8080), `LAN_IP` (autodetectada de la primera interfaz global que no sea
loopback/docker/túnel), `RESULTS_DIR` (`docs/evidencia-capacidad`),
`PG_CONTAINER` (`pp6-capacidad-pg`).

## Solo la medición (servicio ya corriendo)

```bash
PUBLIC_BASE_URL=http://<ip-lan>:<puerto> DB_CONTAINER=<contenedor-pg> \
  node scripts/capacidad/medir-capacidad.mjs \
    --base http://<ip-lan>:<puerto> --out <dir-salida> \
    [--seed 1000] [--warmup 200] [--requests 1000] [--clients 10]
```

- `DB_CONTAINER` es opcional: si está, se consultan conteos y tamaños por
  `docker exec … psql` al terminar la semilla y al final de la corrida.
- `PUBLIC_BASE_URL` es opcional: si está, cada `shortUrl` recibido se
  compara con `<public>/<alias>`.

## Qué hace el medidor

1. **Semilla**: `seed` creaciones por `POST /api/links` (10 clientes).
   Se mide por la API pública a propósito: produce el mismo estado que el
   uso real (misma transacción, mismo avance del generador), sin replicar
   la política de alias en SQL.
2. **Calentamiento**: `warmup` creaciones + `warmup` resoluciones, fuera
   de la estadística (las filas quedan en el CSV con `phase=warmup`).
3. **Creación medida**: `requests` POST con `clients` trabajadores
   concurrentes; cada trabajador emite la siguiente solicitud al
   completar la anterior (modelo cerrado).
4. **Resolución medida**: `requests` GET `/{alias}` sobre alias vigentes
   elegidos al azar del conjunto conocido, sin seguir redirecciones
   (`node:http` no las sigue; se lee `Location` para verificar).
5. **Invariantes**: unicidad de los alias emitidos; `Location` igual al
   destino registrado en cada resolución; muestra de 300 alias
   re-resuelta después de la carga; alias jamás emitidos responden 404
   con la página acordada; una creación adicional por encima del volumen
   de referencia responde 201.

## Criterio

Por operación: `exitosas_y_<=1s / total >= 95 %`, con **cero** errores
inesperados (una respuesta errónea rápida no cuenta como éxito).
Percentiles por rango más cercano sobre las duraciones medidas entre el
envío de la solicitud y la recepción completa de la respuesta.

## Artefactos

En `--out`: `creaciones.csv` y `resoluciones.csv` (una fila por
solicitud, todas las fases), `resumen.json` (percentiles, errores,
invariantes, estado de la base, veredicto), `salida-medicion.txt`,
`entorno.txt`, `db-stats.txt` y `app.log` cuando corre vía el script
orquestador. Resultados y análisis de la corrida de referencia en
[docs/evidencia-capacidad.md](../../docs/evidencia-capacidad.md).
