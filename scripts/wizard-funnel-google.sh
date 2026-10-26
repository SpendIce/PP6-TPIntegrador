#!/usr/bin/env bash
#
# Wizard de la etapa 2: publicar el servicio con Tailscale Funnel y registrar
# el cliente OAuth de Google (issue #28).
#
# Uso:  bash scripts/wizard-funnel-google.sh
#
# Todo lo que está arriba del marcador STAGES es la librería del wizard: no se
# edita. Los pasos de abajo son los de este procedimiento.
#
# El `.env` de este proyecto es formato *properties* (sin comillas ni export),
# así que este wizard usa `env_plain` en lugar de `write_env`.

set -euo pipefail

# ──────────────────────────────────────────────────────────────────────────
# Wizard library: delightful, consistent UX, identical across every wizard.
# ──────────────────────────────────────────────────────────────────────────

if [[ -t 1 ]] && command -v tput >/dev/null 2>&1 && [[ "$(tput colors 2>/dev/null || echo 0)" -ge 8 ]]; then
  BOLD=$(tput bold); DIM=$(tput dim); RESET=$(tput sgr0)
  BLUE=$(tput setaf 4); GREEN=$(tput setaf 2); YELLOW=$(tput setaf 3)
else
  BOLD=""; DIM=""; RESET=""; BLUE=""; GREEN=""; YELLOW=""
fi

TOTAL_STAGES=0

_STAGE_INDEX=0
ENV_FILE="${ENV_FILE:-.env}"
WRITTEN_ENV=()    # KEYs written to ENV_FILE this run
WRITTEN_SECRET=() # secret NAMEs set this run
SKIPPED=()        # things we couldn't do (e.g. gh missing)

_clear() {
  [[ -t 1 ]] || return 0
  if command -v tput >/dev/null 2>&1 && tput clear 2>/dev/null; then return 0; fi
  printf '\033[2J\033[3J\033[H'
}

banner() {
  _clear
  printf '\n%s%s  %s%s\n' "$BOLD" "$BLUE" "$1" "$RESET"
  printf '%s  %s stages%s\n\n' "$DIM" "$TOTAL_STAGES" "$RESET"
  printf '%s  You drive the browser; this wizard tells you exactly what to do and\n' "$DIM"
  printf '  captures the values you copy back. Stop any time with Ctrl-C and re-run\n'
  printf '  later, since it remembers values already saved.%s\n' "$RESET"
  pause "Ready to start?"
}

stage() {
  _clear
  _STAGE_INDEX=$((_STAGE_INDEX + 1))
  printf '\n%s%s▸ Stage %s/%s · %s%s\n' \
    "$BOLD" "$BLUE" "$_STAGE_INDEX" "$TOTAL_STAGES" "$1" "$RESET"
}

say()  { printf '  %s\n' "$1"; }
step() { printf '  %s•%s %s\n' "$BLUE" "$RESET" "$1"; }
note() { printf '  %s%s%s\n' "$DIM" "$1" "$RESET"; }
warn() { printf '  %s⚠ %s%s\n' "$YELLOW" "$1" "$RESET"; }

open_url() {
  local url="$1"
  printf '  %s↗ opening%s %s\n' "$GREEN" "$RESET" "$url"
  { if   command -v wslview     >/dev/null 2>&1; then wslview "$url"
    elif command -v explorer.exe >/dev/null 2>&1; then explorer.exe "$url"
    elif command -v xdg-open    >/dev/null 2>&1; then xdg-open "$url"
    elif command -v open        >/dev/null 2>&1; then open "$url"
    else false; fi
  } >/dev/null 2>&1 || warn "couldn't open a browser, so visit it manually: $url"
}

pause() {
  printf '  %s%s%s ' "$DIM" "${1:-Press Enter to continue}" "$RESET"
  read -r _ || true
}

confirm() {
  local reply=""
  printf '  %s? %s [y/N] ' "$YELLOW" "$1"
  read -r reply || true
  [[ "$reply" =~ ^[Yy] ]]
}

_existing() {
  [[ -f "$ENV_FILE" ]] || return 1
  local line value sq="'\\''"; line=$(grep -E "^${1}=" "$ENV_FILE" | tail -n1) || return 1
  value="${line#*=}"
  if [[ "$value" == \'*\' ]]; then value="${value:1:${#value}-2}"; value="${value//"$sq"/\'}"
  elif [[ "$value" == \"*\" ]]; then
    value="${value:1:${#value}-2}"
    [[ "$value" == *[\\\$\"]* ]] && return 1
  fi
  printf '%s' "$value"
}

ask() {
  local key="$1" prompt="$2" current input s=$'\001' e=$'\002' p rc=0
  current=$(_existing "$key" || true)
  p="  $s$BOLD$e$prompt$s$RESET$e "
  [[ -n "$current" ]] && p+="$s$DIM$e[Enter keeps current]$s$RESET$e "
  read -e -r -p "$p" input || rc=1
  if (( rc )) && [[ -z "$input" ]]; then return 1; fi
  [[ -z "$input" && -n "$current" ]] && input="$current"
  printf -v "$key" '%s' "$input"
}

ask_secret() {
  local key="$1" prompt="$2" current input rc=0
  current=$(_existing "$key" || true)
  if [[ -n "$current" ]]; then
    printf '  %s%s%s %s[Enter keeps current]%s ' "$BOLD" "$prompt" "$RESET" "$DIM" "$RESET"
  else
    printf '  %s%s%s ' "$BOLD" "$prompt" "$RESET"
  fi
  read -rs input || rc=1
  printf '\n'
  if (( rc )) && [[ -z "$input" ]]; then return 1; fi
  [[ -z "$input" && -n "$current" ]] && input="$current"
  printf -v "$key" '%s' "$input"
}

write_env() {
  local key="$1" value="$2" tmp sq="'\\''"
  [[ -e "$ENV_FILE" ]] || (umask 077 && : > "$ENV_FILE")
  tmp=$(mktemp)
  grep -vE "^${key}=" "$ENV_FILE" > "$tmp" || true
  printf "%s='%s'\n" "$key" "${value//\'/$sq}" >> "$tmp"
  cat "$tmp" > "$ENV_FILE"
  rm -f "$tmp"
  printf -v "$key" '%s' "$value"
  WRITTEN_ENV+=("$key")
  printf '  %s✓ wrote%s %s → %s\n' "$GREEN" "$RESET" "$key" "$ENV_FILE"
}

set_secret() {
  local name="$1" value="$2"
  if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    if printf '%s' "$value" | gh secret set "$name" >/dev/null 2>&1; then
      WRITTEN_SECRET+=("$name")
      printf '  %s✓ set%s GitHub secret %s\n' "$GREEN" "$RESET" "$name"
      return
    fi
  fi
  SKIPPED+=("GitHub secret $name (set it manually: gh secret set $name)")
  warn "skipped GitHub secret $name: gh not ready; set it later"
}

set_var() {
  local name="$1" value="$2"
  if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    if gh variable set "$name" --body "$value" >/dev/null 2>&1; then
      printf '  %s✓ set%s GitHub variable %s\n' "$GREEN" "$RESET" "$name"
      return
    fi
  fi
  SKIPPED+=("GitHub variable $name")
  warn "skipped GitHub variable $name, gh not ready; set it later"
}

finish() {
  _clear
  printf '\n%s%s  ✓ Setup complete%s\n' "$BOLD" "$GREEN" "$RESET"
  (( ${#WRITTEN_ENV[@]} ))    && note "wrote ${#WRITTEN_ENV[@]} value(s) to $ENV_FILE: ${WRITTEN_ENV[*]}"
  (( ${#WRITTEN_SECRET[@]} )) && note "set ${#WRITTEN_SECRET[@]} GitHub secret(s): ${WRITTEN_SECRET[*]}"
  if (( ${#SKIPPED[@]} )); then
    printf '\n'; warn "still to do by hand:"
    for s in "${SKIPPED[@]}"; do note "  - $s"; done
  fi
  printf '\n'
}

# ──────────────────────────────────────────────────────────────────────────
# STAGES
# ──────────────────────────────────────────────────────────────────────────

# El .env de este repo es properties: SIN comillas, SIN export. Una comilla
# termina formando parte del valor y corta el arranque.
env_plain() {
  local key="$1" value="$2" tmp
  [[ -e "$ENV_FILE" ]] || (umask 077 && : > "$ENV_FILE")
  tmp=$(mktemp)
  grep -vE "^${key}=" "$ENV_FILE" > "$tmp" || true
  printf '%s=%s\n' "$key" "$value" >> "$tmp"
  cat "$tmp" > "$ENV_FILE"
  rm -f "$tmp"
  printf -v "$key" '%s' "$value"
  if [[ " ${WRITTEN_ENV[*]} " != *" $key "* ]]; then WRITTEN_ENV+=("$key"); fi
  printf '  %s✓ wrote%s %s → %s\n' "$GREEN" "$RESET" "$key" "$ENV_FILE"
}

run_wizard() {

TOTAL_STAGES=5

banner "Etapa 2 · Tailscale Funnel + cliente OAuth de Google"

# ── 1 ─────────────────────────────────────────────────────────────────────
stage "Tailscale: la URL pública HTTPS"
say "Google exige una URI de redirección HTTPS y no acepta IPs: la demo sale"
say "por Tailscale Funnel y esa URL pasa a ser PUBLIC_BASE_URL."
TS_DNS=""
if command -v tailscale >/dev/null 2>&1; then
  TS_STATE=$(tailscale status --json 2>/dev/null | jq -r '.BackendState' 2>/dev/null || echo "?")
  TS_DNS=$(tailscale status --json 2>/dev/null | jq -r '.Self.DNSName' 2>/dev/null | sed 's/\.$//' || true)
  [[ "$TS_STATE" == "Running" ]] && say "Tailscale conectado como $TS_DNS" \
    || warn "Tailscale no está conectado (estado: $TS_STATE). Corré: tailscale up"
else
  warn "falta el binario tailscale: instalalo con pacman -S tailscale"
  SKIPPED+=("instalar y conectar Tailscale")
fi
step "Habilitá los certificados HTTPS de la tailnet (una sola vez):"
note "Admin console → DNS → HTTPS Certificates → Enable HTTPS"
open_url "https://login.tailscale.com/admin/dns"
step "Habilitá Funnel para este nodo en la política de la tailnet (una sola vez):"
note "Admin console → Access controls → agregá nodeAttrs funnel para este nodo"
open_url "https://login.tailscale.com/admin/acls/file"
pause "Cuando estén las dos cosas habilitadas, Enter."
[[ -z "$TS_DNS" ]] && ask TS_DNS "Nombre DNS de la máquina (sin https://):"
PUBLIC_BASE_URL="https://$TS_DNS"
say "La URL pública va a ser: $PUBLIC_BASE_URL"
if confirm "¿Publico el puerto 8080 con 'tailscale funnel --bg 8080' ahora?"; then
  tailscale funnel --bg 8080 || warn "falló: revisá 'tailscale funnel status' y los permisos de la tailnet"
fi
say "Comprobación: 'tailscale funnel status' debe mostrar $PUBLIC_BASE_URL → 127.0.0.1:8080"
note "La app tiene que estar corriendo (./gradlew bootRun) para que responda."

# ── 2 ─────────────────────────────────────────────────────────────────────
stage "Google Cloud: proyecto y pantalla de consentimiento"
say "Un cliente «Externo» y «En producción» deja entrar a cualquier cuenta"
say "pública de Google sin lista de usuarios de prueba."
open_url "https://console.cloud.google.com/projectcreate"
step "Creá un proyecto (por ejemplo 'pp6-acortador') y esperá a que quede seleccionado."
open_url "https://console.cloud.google.com/auth/overview"
note "Si la consola muestra 'OAuth consent screen' en lugar de 'Google Auth Platform',"
note "el camino es APIs & Services → OAuth consent screen."
step "User type: External → Create."
step "Publishing status: Publish app → In production."
step "Data access: agregá solo los alcances openid, email y profile."
note "Los tres son no sensibles: no piden verificación de Google."
step "En Audience, dejá la app en producción (no en Testing)."
pause "Cuando la app figure 'In production', Enter."

# ── 3 ─────────────────────────────────────────────────────────────────────
stage "Google Cloud: cliente OAuth web"
say "El cliente web necesita la URI exacta a la que Spring vuelve después del login."
REDIRECT_URI="$PUBLIC_BASE_URL/login/oauth2/code/google"
step "Create credentials → OAuth client ID → Application type: Web application."
step "Authorized JavaScript origins: $PUBLIC_BASE_URL"
step "Authorized redirect URIs: $REDIRECT_URI"
note "Si el login de la etapa 2 cambia el camino de vuelta, esta URI tiene que coincidir"
note "exactamente con el que declare la implementación del issue #19."
step "Create → copiá el Client ID y el Client secret."
ask GOOGLE_CLIENT_ID "Pegá el Client ID (…apps.googleusercontent.com):"
ask_secret GOOGLE_CLIENT_SECRET "Pegá el Client secret:"

# ── 4 ─────────────────────────────────────────────────────────────────────
stage "Escribir el .env local (nunca se commitea)"
note "Formato properties: sin comillas ni export, como el resto del archivo."
env_plain PUBLIC_BASE_URL "$PUBLIC_BASE_URL"
env_plain GOOGLE_CLIENT_ID "$GOOGLE_CLIENT_ID"
env_plain GOOGLE_CLIENT_SECRET "$GOOGLE_CLIENT_SECRET"
note "OWN_ORIGINS no hace falta: PUBLIC_BASE_URL ya se suma a los orígenes propios."
if confirm "¿Agrego también NAMESPACE_GRACE_PERIOD=PT2M para poder ver el fin de la gracia?"; then
  env_plain NAMESPACE_GRACE_PERIOD "PT2M"
fi
say "Reiniciá el servicio para que tome el .env: Ctrl-C en bootRun y ./gradlew bootRun."

# ── 5 ─────────────────────────────────────────────────────────────────────
stage "Verificar la publicación"
if command -v curl >/dev/null 2>&1; then
  CODE=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 "$PUBLIC_BASE_URL/" || echo "000")
  if [[ "$CODE" == "200" ]]; then
    say "La URL pública responde 200: $PUBLIC_BASE_URL"
  else
    warn "La URL pública respondió $CODE."
    note "Revisá que la app escuche en 8080, 'tailscale funnel status' y los certificados HTTPS."
  fi
fi
open_url "$PUBLIC_BASE_URL"
say "Cuando el login con Google esté implementado (#19), este es el cierre del #28:"
step "Abrí $PUBLIC_BASE_URL/login en una ventana privada con una cuenta que NO sea del equipo."
step "Tiene que entrar sin estar en la lista de usuarios de prueba."
step "Pedile al docente que pruebe temprano su cuenta: una cuenta de Workspace puede"
note "estar bloqueada por su organización aunque la app esté en producción."
pause "Cuando lo hayas probado, Enter."
note "Dejá el resultado en docs/despliegue-funnel-google.md y cerrá el issue #28 con un comentario."

finish
}

run_wizard "$@"; exit
