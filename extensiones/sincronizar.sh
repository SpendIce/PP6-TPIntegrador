#!/usr/bin/env bash
# Copia el codigo de las extensiones a cada paquete de navegador.
#
# Fuentes:
# - compartido/                        -> archivos propios del popup.
# - src/main/resources/static/         -> qr-code.js y vendor/qrcode.js,
#   el mismo stack QR que usa la web (issue #7): un solo vendor y un
#   solo encoder PNG para todo el proyecto.
#
# Volver a ejecutar este script tras editar cualquiera de las fuentes;
# los tests verifican la consistencia byte a byte.
set -euo pipefail
cd "$(dirname "$0")"

STATIC="../src/main/resources/static"
SHARED_FILES="popup.html popup.css popup.js api.js"

for target in chrome firefox; do
  for file in $SHARED_FILES; do
    cp "compartido/$file" "$target/$file"
  done
  cp "$STATIC/qr-code.js" "$target/qr-code.js"
  mkdir -p "$target/vendor"
  cp "$STATIC/vendor/qrcode.js" "$target/vendor/qrcode.js"
  cp "$STATIC/vendor/qrcode-generator.LICENSE.txt" "$target/vendor/qrcode-generator.LICENSE.txt"
done

echo "Sincronizados chrome/ y firefox/ (compartido/ + stack QR de la web)."
