# Demo integrada de la etapa 1

Recorrido automatizable de la demostración final (issue #11; spec padre
#1). Ejecuta el entorno documentado de verdad y ejercita por HTTP la
creación, el resultado completo, el QR (decodificándolo), la
redirección, los errores del contrato, el vencimiento real con duración
corta, la continuidad tras reinicios, el reciclaje con historial y la
resolución de un QR viejo a la asignación nueva.

## Ejecución completa

```bash
scripts/demo/ejecutar-demo.sh
```

Desde la raíz del repo: PostgreSQL efímero (`--rm`, contenedor
`pp6-demo-pg`, host `127.0.0.1:55434` para no pisar otros servicios),
servicio con `./gradlew bootRun` y `PUBLIC_BASE_URL` en la IP LAN
detectada, `OWN_ORIGINS` con los orígenes equivalentes del equipo, dos
reinicios reales sobre la misma base (fase B con `DEMO_SHORT_DURATION`,
por defecto `PT1M`, solo para observar el vencimiento; la entrega queda
en 60 minutos) y evidencia cruda en `RESULTS_DIR`.

Variables opcionales: `DEMO_DB_PORT` (55434), `APP_PORT` (8080),
`LAN_IP` (autodetectada), `RESULTS_DIR` (`docs/evidencia-demo`),
`PG_CONTAINER` (`pp6-demo-pg`), `DEMO_SHORT_DURATION` (`PT1M`).

## Solo el recorrido (servicio ya corriendo)

```bash
PUBLIC_BASE_URL=http://<ip-lan>:<puerto> DB_CONTAINER=<contenedor-pg> \
  DEMO_EXPECTED_DURATION_MIN=<minutos configurados> \
  node scripts/demo/recorrido-demo.mjs \
    --phase a|b|c --base http://<ip-lan>:<puerto> --out <dir>
```

Las fases comparten estado por `<dir>/estado.json`; el orden real es
a → reinicio con duración corta → b → reinicio a 60 min → c.

## Lo que verifica cada fase

- **a**: web y estáticos servidos; 201 con `shortUrl` LAN + `expiresAt`
  +60 min + `reuseNotice`; PNG del QR decodificado (UPNG+jsQR, y
  `zbarimg` si está); 302 verbatim; destinos LAN/credenciales/
  parámetros/fragmento/8.192 aceptados; rechazos 400 por código
  incluyendo orígenes propios configurados; rechazos sin consumir alias;
  alias desconocido → 404 con la página acordada.
- **b**: continuidad tras reinicio; `vence_en` no se recalcula ni se
  renueva por visitas; vencimiento real → 404; la creación siguiente
  recicla el alias (historial conservado, verificado por psql); el QR
  viejo resuelve al nuevo destino; con vencidos de 1 y 2 caracteres se
  reutiliza uno de 1.
- **c**: continuidad tras el segundo reinicio y restitución de la
  duración de entrega (expiresAt +60 min).

Resultados, números y el checklist manual (complementos, celular,
verificación visual) en [docs/demo-etapa-1.md](../../docs/demo-etapa-1.md).
