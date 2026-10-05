#!/usr/bin/env bash
# Medición de capacidad y respuesta del acortador (issue #9; spec padre #1
# «Capacidad y respuesta» / «Prueba de capacidad»).
#
# Orquesta la prueba completa en este equipo:
#   1. Levanta un PostgreSQL 16.4 propio y descartable (--rm) en
#      127.0.0.1:$CAP_DB_PORT — no toca el `acortador-postgres` de
#      desarrollo ni ningún servicio que ya ocupe 5432.
#   2. Arranca el servicio con `mvn spring-boot:run` (procedimiento de
#      ejecución documentado) con PUBLIC_BASE_URL=http://<IP-LAN>:$APP_PORT.
#   3. Comprueba la alcanzabilidad por la dirección LAN (si la interfaz o
#      el firewall no la permiten, mide contra loopback y lo registra).
#   4. Ejecuta scripts/capacidad/medir-capacidad.mjs: semilla de 1.000
#      asignaciones vigentes vía API, calentamiento, 10 clientes
#      concurrentes, 1.000 creaciones y 1.000 resoluciones medidas por
#      separado (resolución sin seguir redirects), invariantes y la
#      creación posterior al volumen de referencia.
#   5. Guarda evidencia cruda en $RESULTS_DIR y apaga todo.
#
# Configuración por entorno (valores de la corrida de referencia):
#   CAP_DB_PORT=55433  APP_PORT=8080  LAN_IP=<autodetectada>
#   RESULTS_DIR=docs/evidencia-capacidad  PG_CONTAINER=pp6-capacidad-pg
#
# Código de salida: el veredicto de la medición (0 = CUMPLE).

set -euo pipefail
cd "$(dirname "$0")/../.."
REPO_ROOT="$PWD"

CAP_DB_PORT="${CAP_DB_PORT:-55433}"
APP_PORT="${APP_PORT:-8080}"
PG_CONTAINER="${PG_CONTAINER:-pp6-capacidad-pg}"
RESULTS_DIR="${RESULTS_DIR:-docs/evidencia-capacidad}"
DB_NAME=acortador
DB_USER=acortador
DB_PASSWORD=acortador

# --- Prerrequisitos ----------------------------------------------------------

for cmd in docker node mvn curl setsid; do
  command -v "$cmd" >/dev/null || { echo "Falta $cmd" >&2; exit 64; }
done

port_free() { ! ss -ltn "sport = :$1" 2>/dev/null | tail -n +2 | grep -q .; }
port_free "$CAP_DB_PORT" || { echo "Puerto $CAP_DB_PORT ocupado (CAP_DB_PORT)" >&2; exit 65; }
port_free "$APP_PORT" || { echo "Puerto $APP_PORT ocupado (APP_PORT)" >&2; exit 65; }

LAN_IP="${LAN_IP:-$(ip -4 -o addr show scope global | grep -vE ' (lo|docker[0-9]*|br-|veth|tun)' | awk '{split($4,a,"/"); print a[1]; exit}')}"
LAN_IP="${LAN_IP:-127.0.0.1}"
PUBLIC_BASE_URL="http://$LAN_IP:$APP_PORT"
mkdir -p "$RESULTS_DIR"
APP_LOG="$REPO_ROOT/$RESULTS_DIR/app.log"
OUT_DIR="$REPO_ROOT/$RESULTS_DIR"

# --- Ciclo de vida -----------------------------------------------------------

APP_PID=""
cleanup() {
  local rc=$?
  if [ -n "$APP_PID" ]; then
    kill -TERM -- -"$APP_PID" 2>/dev/null || true
    for _ in $(seq 1 30); do kill -0 "$APP_PID" 2>/dev/null || break; sleep 1; done
    kill -KILL -- -"$APP_PID" 2>/dev/null || true
  fi
  docker stop "$PG_CONTAINER" >/dev/null 2>&1 || true
  exit "$rc"
}
trap cleanup EXIT INT TERM

echo "== PostgreSQL de medición ($PG_CONTAINER en 127.0.0.1:$CAP_DB_PORT) =="
docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || true
docker run -d --rm --name "$PG_CONTAINER" \
  -p "127.0.0.1:$CAP_DB_PORT:5432" \
  -e POSTGRES_DB="$DB_NAME" -e POSTGRES_USER="$DB_USER" -e POSTGRES_PASSWORD="$DB_PASSWORD" \
  postgres:16.4-alpine >/dev/null
for _ in $(seq 1 60); do
  docker exec "$PG_CONTAINER" pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$PG_CONTAINER" pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null

echo "== Servicio (mvn spring-boot:run, PUBLIC_BASE_URL=$PUBLIC_BASE_URL) =="
DB_HOST=127.0.0.1 DB_PORT="$CAP_DB_PORT" DB_NAME="$DB_NAME" DB_USER="$DB_USER" DB_PASSWORD="$DB_PASSWORD" \
SERVER_PORT="$APP_PORT" PUBLIC_BASE_URL="$PUBLIC_BASE_URL" \
  setsid mvn spring-boot:run >"$APP_LOG" 2>&1 &
APP_PID=$!

READY=""
for _ in $(seq 1 240); do
  if curl -sf -o /dev/null --max-time 3 "http://127.0.0.1:$APP_PORT/"; then READY=1; break; fi
  kill -0 "$APP_PID" 2>/dev/null || { echo "El proceso del servicio murió; últimas líneas:"; tail -20 "$APP_LOG"; exit 1; }
  sleep 1
done
[ -n "$READY" ] || { echo "Timeout esperando el servicio; últimas líneas:"; tail -20 "$APP_LOG"; exit 1; }

LAN_CHECK=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://$LAN_IP:$APP_PORT/" || echo "FALLO")
if [ "$LAN_CHECK" = "200" ] && [ "$LAN_IP" != "127.0.0.1" ]; then
  BASE_URL="http://$LAN_IP:$APP_PORT"
else
  BASE_URL="http://127.0.0.1:$APP_PORT"
  [ "$LAN_IP" = "127.0.0.1" ] || echo "Aviso: $LAN_IP:$APP_PORT no respondió ($LAN_CHECK); midiendo por loopback."
fi
echo "== Servicio listo. LAN $LAN_IP:$APP_PORT -> HTTP $LAN_CHECK. Midiendo contra $BASE_URL =="

MEDIR_RC=0
DB_CONTAINER="$PG_CONTAINER" PUBLIC_BASE_URL="$PUBLIC_BASE_URL" \
  node scripts/capacidad/medir-capacidad.mjs \
    --base "$BASE_URL" --out "$OUT_DIR" \
    2>&1 | tee "$OUT_DIR/salida-medicion.txt" || MEDIR_RC=$?

# --- Evidencia de entorno y volumen ------------------------------------------

{
  echo "date: $(date --iso-8601=seconds)"
  echo "hostname: $(hostname)"
  echo "kernel: $(uname -srmo)"
  echo "os: $(. /etc/os-release && echo "$PRETTY_NAME")"
  echo "cpu: $(lscpu | sed -n 's/^Model name:[[:space:]]*//p' | head -1)"
  echo "cores: $(nproc)"
  echo "memory: $(free -h | awk '/^Mem:/ {print $2 " total, " $7 " available"}')"
  echo "java: $(java -version 2>&1 | head -1)"
  echo "maven: $(mvn -version 2>/dev/null | head -1)"
  echo "node: $(node --version)"
  echo "docker: $(docker version --format '{{.Server.Version}}' 2>/dev/null)"
  echo "postgres_image: $(docker image inspect postgres:16.4-alpine --format '{{.Id}}' 2>/dev/null)"
  echo "postgres_server: $(docker exec "$PG_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAc 'show server_version' 2>/dev/null)"
  echo "app_port: $APP_PORT | db_host_port: $CAP_DB_PORT | db_container: $PG_CONTAINER"
  echo "public_base_url: $PUBLIC_BASE_URL | measured_base: $BASE_URL | lan_check_http: $LAN_CHECK"
  echo "link_duration: PT60M (default) | hikari pool & tomcat: Boot 3.5.16 defaults"
} > "$OUT_DIR/entorno.txt"

{
  docker exec "$PG_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -c "
    select 'assignments_total', count(*)::text from asignacion
    union all select 'aliases_total', count(*)::text from alias
    union all select 'active_assignments', count(*)::text from alias al
      join asignacion a on a.alias_codigo = al.codigo and a.id = al.asignacion_actual_id
      where a.vence_en > now()
    union all select 'proximo_indice', proximo_indice::text from generador_alias
    union all select 'asignacion_size', pg_size_pretty(pg_total_relation_size('asignacion'))
    union all select 'alias_size', pg_size_pretty(pg_total_relation_size('alias'))
    union all select 'database_size', pg_size_pretty(pg_database_size('$DB_NAME'));"
} > "$OUT_DIR/db-stats.txt" 2>/dev/null || true

echo "== Fin. Artefactos en $RESULTS_DIR (rc=$MEDIR_RC) =="
exit "$MEDIR_RC"
