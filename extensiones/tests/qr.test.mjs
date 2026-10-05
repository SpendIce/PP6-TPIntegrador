// Verificacion del QR dentro de cada paquete de navegador: las copias
// de vendor/qrcode.js y qr-code.js (el mismo stack que la web, issue #7)
// se cargan desde chrome/ y firefox/ como las cargara el popup, y el PNG
// generado se decodifica con bibliotecas independientes vendorizadas para
// pruebas (UPNG.js + jsQR, ver src/test/js/vendor/README.md).

import { test } from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { decodePngToRgba, decodeQrText } from "../../src/test/js/png-decode.mjs";

const require = createRequire(import.meta.url);

const SHORT_URL = "http://192.168.50.10:8080/7";

for (const browser of ["chrome", "firefox"]) {
  test(`${browser}: el paquete produce un PNG que decodifica al shortUrl`, () => {
    const qrcode = require(`../${browser}/vendor/qrcode.js`);
    const QrPng = require(`../${browser}/qr-code.js`);

    const png = QrPng.encode(qrcode, SHORT_URL);
    const { rgba, width, height } = decodePngToRgba(png);
    assert.ok(width >= 128 && width === height, "imagen escaneable y cuadrada");
    assert.equal(decodeQrText(rgba, width, height), SHORT_URL);
  });

  test(`${browser}: suggestedFileName arma el nombre de descarga por alias`, () => {
    const QrPng = require(`../${browser}/qr-code.js`);
    assert.equal(QrPng.suggestedFileName("aB3"), "qr-aB3.png");
  });
}
