#!/usr/bin/env bash
# Demo integrada de la etapa 1 (issue #11; spec padre #1).
#
# Orquesta el recorrido automatizable de la demostración en este equipo:
#   1. Levanta un PostgreSQL 16.4 propio y descartable (--rm, contenedor
#      pp6-demo-pg) publicado en 127.0.0.1:$DEMO_DB_PORT — no toca el
#      acortador-postgres de desarrollo ni servicios ajenos en 5432.
#   2. Arranca el servicio con `./gradlew bootRun` (procedimiento
#      documentado) con PUBLIC_BASE_URL=http://<IP-LAN>:$APP_PORT y
#      OWN_ORIGINS con los orígenes equivalentes del equipo (hostname y
#      demás IPs locales), y corre scripts/demo/recorrido-demo.mjs.
#   3. Reinicia el servicio dos veces sobre la MISMA base: primero con
#      una duración corta ($DEMO_SHORT_DURATION, defecto PT1M) para
#      observar el vencimiento real, el reciclaje con historial y un QR
#      viejo resolviendo a la asignación nueva; después de nuevo con 60
#      minutos, la duración de entrega.
#   4. Guarda la evidencia cruda en $RESULTS_DIR y apaga todo.
#
# Lo físico no se simula: instalar los complementos, el recorrido con
# clics en navegadores reales y el escaneo del QR con un celular quedan
# como checklist para el humano en docs/demo-etapa-1.md.
#
# Configuración por entorno:
#   DEMO_DB_PORT=55434  APP_PORT=8080  LAN_IP=<autodetectada>
#   RESULTS_DIR=docs/evidencia-demo  PG_CONTAINER=pp6-demo-pg
#   DEMO_SHORT_DURATION=PT1M
#
# Código de salida: 0 si todas las verificaciones de las tres fases
# pasaron; distinto de 0 si hubo al menos una FALLA.
set -euo pipefail
cd "$(dirname "$0")/../.."
REPO_ROOT="$PWD"

DEMO_DB_PORT="${DEMO_DB_PORT:-55434}"
APP_PORT="${APP_PORT:-8080}"
PG_CONTAINER="${PG_CONTAINER:-pp6-demo-pg}"
RESULTS_DIR="${RESULTS_DIR:-docs/evidencia-demo}"
DEMO_SHORT_DURATION="${DEMO_SHORT_DURATION:-PT1M}"
DB_NAME=acortador
DB_USER=acortador
DB_PASSWORD=acortador

# Minutos de la duración corta (para las verificaciones de expiresAt).
case "$DEMO_SHORT_DURATION" in
  PT*M) SHORT_MIN="${DEMO_SHORT_DURATION#PT}"; SHORT_MIN="${SHORT_MIN%M}" ;;
  PT*S) SHORT_MIN="$(awk -v s="${DEMO_SHORT_DURATION#PT}" 'BEGIN{sub(/S$/,"",s); print s/60}')" ;;
  *) echo "DEMO_SHORT_DURATION debe ser ISO-8601 PTnM o PTnS" >&2; exit 64 ;;
esac

for cmd in docker node curl setsid; do
  command -v "$cmd" >/dev/null || { echo "Falta $cmd" >&2; exit 64; }
done
[ -x ./gradlew ] || { echo "Falta ./gradlew (o no es ejecutable)" >&2; exit 64; }
command -v zbarimg >/dev/null \
  || echo "Aviso: zbarimg no está instalado; la decodificación del QR usa solo UPNG+jsQR."

port_free() { ! ss -ltn "sport = :$1" 2>/dev/null | tail -n +2 | grep -q .; }
port_free "$DEMO_DB_PORT" || { echo "Puerto $DEMO_DB_PORT ocupado (DEMO_DB_PORT)" >&2; exit 65; }
port_free "$APP_PORT" || { echo "Puerto $APP_PORT ocupado (APP_PORT)" >&2; exit 65; }

# IP LAN: primera interfaz global que no sea loopback, docker, bridge ni túnel.
LAN_IP="${LAN_IP:-$(ip -4 -o addr show scope global | grep -vE ' (lo|docker[0-9]*|br-|veth|tun)' | awk '{split($4,a,"/"); print a[1]; exit}')}"
LAN_IP="${LAN_IP:-127.0.0.1}"
PUBLIC_BASE_URL="http://$LAN_IP:$APP_PORT"

# Orígenes propios equivalentes: el hostname del equipo y las demás IPs
# locales globales (otras interfaces son la misma máquina). El origen de
# PUBLIC_BASE_URL y los loopback los suma solo el servicio.
OWN_ORIGINS="http://$(hostname -s):$APP_PORT"
for ip in $(ip -4 -o addr show scope global | grep -vE ' (lo)\b' | awk '{split($4,a,"/"); print a[1]}' | grep -vx "$LAN_IP"); do
  OWN_ORIGINS="$OWN_ORIGINS,http://$ip:$APP_PORT"
done

OUT_DIR="$REPO_ROOT/$RESULTS_DIR"
mkdir -p "$OUT_DIR"
exec > >(tee "$OUT_DIR/salida-demo.txt") 2>&1

APP_PID=""
stop_service() {
  [ -n "$APP_PID" ] || return 0
  kill -TERM -- -"$APP_PID" 2>/dev/null || true
  for _ in $(seq 1 30); do kill -0 "$APP_PID" 2>/dev/null || break; sleep 1; done
  kill -KILL -- -"$APP_PID" 2>/dev/null || true
  APP_PID=""
}
cleanup() {
  local rc=$?
  stop_service
  docker stop "$PG_CONTAINER" >/dev/null 2>&1 || true
  exit "$rc"
}
trap cleanup EXIT INT TERM

start_service() {
  local duration="$1" log="$2"
  DB_HOST=127.0.0.1 DB_PORT="$DEMO_DB_PORT" DB_NAME="$DB_NAME" DB_USER="$DB_USER" DB_PASSWORD="$DB_PASSWORD" \
  SERVER_PORT="$APP_PORT" PUBLIC_BASE_URL="$PUBLIC_BASE_URL" OWN_ORIGINS="$OWN_ORIGINS" \
  LINK_DURATION="$duration" \
    setsid ./gradlew --no-daemon bootRun --console=plain >"$log" 2>&1 &
  APP_PID=$!
  for _ in $(seq 1 240); do
    curl -sf -o /dev/null --max-time 3 "http://127.0.0.1:$APP_PORT/" && return 0
    kill -0 "$APP_PID" 2>/dev/null || { echo "El servicio murió; últimas líneas:"; tail -20 "$log"; return 1; }
    sleep 1
  done
  echo "Timeout esperando el servicio; últimas líneas:"; tail -20 "$log"; return 1
}

echo "== Demo integrada etapa 1 =="
echo "   PUBLIC_BASE_URL=$PUBLIC_BASE_URL"
echo "   OWN_ORIGINS=$OWN_ORIGINS"
echo "   PostgreSQL: $PG_CONTAINER en 127.0.0.1:$DEMO_DB_PORT"

docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || true
docker run -d --rm --name "$PG_CONTAINER" \
  -p "127.0.0.1:$DEMO_DB_PORT:5432" \
  -e POSTGRES_DB="$DB_NAME" -e POSTGRES_USER="$DB_USER" -e POSTGRES_PASSWORD="$DB_PASSWORD" \
  postgres:16.4-alpine >/dev/null
for _ in $(seq 1 60); do
  docker exec "$PG_CONTAINER" pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$PG_CONTAINER" pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null
docker exec "$PG_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -c \
  "select 'postgresql listo', version()" >/dev/null

RC=0
run_phase() { # fase minutos_esperados
  DEMO_EXPECTED_DURATION_MIN="$2" \
  DB_CONTAINER="$PG_CONTAINER" PUBLIC_BASE_URL="$PUBLIC_BASE_URL" \
  DEMO_EXTRA_OWN_ORIGINS="$OWN_ORIGINS" \
    node scripts/demo/recorrido-demo.mjs --phase "$1" --base "$PUBLIC_BASE_URL" --out "$OUT_DIR" \
    || RC=1
}

# --- Fase A: entorno documentado, duración de entrega -------------------------
echo "== Arranque 1/3: LINK_DURATION=PT60M (duración de entrega) =="
start_service PT60M "$OUT_DIR/app-fase-a.log"
LAN_CHECK=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$PUBLIC_BASE_URL/" || echo "FALLO")
echo "Chequeo de alcanzabilidad LAN $PUBLIC_BASE_URL -> HTTP $LAN_CHECK"
run_phase a 60
stop_service

# --- Fase B: duración corta, vencimiento real ---------------------------------
echo "== Arranque 2/3: LINK_DURATION=$DEMO_SHORT_DURATION (vencimiento observable) =="
start_service "$DEMO_SHORT_DURATION" "$OUT_DIR/app-fase-b.log"
run_phase b "$SHORT_MIN"
stop_service

# --- Fase C: restitución a 60 min ---------------------------------------------
echo "== Arranque 3/3: LINK_DURATION=PT60M (restitución de la entrega) =="
start_service PT60M "$OUT_DIR/app-fase-c.log"
run_phase c 60

# --- Evidencia de entorno y estado final de la base ---------------------------
{
  echo "date: $(date --iso-8601=seconds)"
  echo "hostname: $(hostname)"
  echo "kernel: $(uname -srmo)"
  echo "os: $(. /etc/os-release && echo "$PRETTY_NAME")"
  echo "java: $(java -version 2>&1 | head -1)"
  echo "gradle: $(./gradlew -q --version 2>/dev/null | awk '/^Gradle /{print $2; exit}')"
  echo "node: $(node --version)"
  echo "zbarimg: $(zbarimg --version 2>/dev/null || echo 'no instalado')"
  echo "docker: $(docker version --format '{{.Server.Version}}' 2>/dev/null)"
  echo "postgres_server: $(docker exec "$PG_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAc 'show server_version' 2>/dev/null)"
  echo "app_port: $APP_PORT | db_host_port: $DEMO_DB_PORT | db_container: $PG_CONTAINER"
  echo "public_base_url: $PUBLIC_BASE_URL | lan_check_http: $LAN_CHECK"
  echo "own_origins: $OWN_ORIGINS"
  echo "link_duration: PT60M (fases a/c) | $DEMO_SHORT_DURATION (fase b, vencimiento observable)"
} > "$OUT_DIR/entorno.txt"

{
  docker exec "$PG_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -c "
    select 'assignments_total', count(*)::text from asignacion
    union all select 'aliases_total', count(*)::text from alias
    union all select 'active_assignments', count(*)::text from alias al
      join asignacion a on a.alias_codigo = al.codigo and a.id = al.asignacion_actual_id
      where a.vence_en > now()
    union all select 'proximo_indice', proximo_indice::text from generador_alias;"
  echo
  echo "-- historial por alias (id | alias | destino | creada_en | vence_en) --"
  docker exec "$PG_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -c \
    "select id, alias_codigo, left(destino, 60) as destino, creada_en, vence_en from asignacion order by id;"
} > "$OUT_DIR/db-stats.txt" 2>/dev/null || true

stop_service
echo "== Fin. Artefactos en $RESULTS_DIR (rc=$RC) =="
exit "$RC"
