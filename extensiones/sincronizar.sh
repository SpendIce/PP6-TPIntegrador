#!/usr/bin/env bash
# Copia el codigo compartido a cada paquete de navegador.
# Fuente de verdad: compartido/. Los directorios chrome/ y firefox/ solo
# agregan su manifest.json; hay que volver a ejecutar este script tras
# editar compartido/ (los tests verifican la consistencia).
set -euo pipefail
cd "$(dirname "$0")"

SHARED_FILES="popup.html popup.css popup.js api.js png.js qr.js"

for target in chrome firefox; do
  for file in $SHARED_FILES; do
    cp "compartido/$file" "$target/$file"
  done
  mkdir -p "$target/vendor"
  cp compartido/vendor/qrcodegen.js "$target/vendor/qrcodegen.js"
done

echo "Sincronizados chrome/ y firefox/ desde compartido/."
