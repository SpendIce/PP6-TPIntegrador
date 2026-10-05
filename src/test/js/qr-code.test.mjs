// Pruebas del QR del cliente web (issue #7). El AC exige que el PNG
// descargable codifique exactamente el `shortUrl` devuelto por la API
// (no la URL de destino). La verificación decodifica el PNG real con
// bibliotecas vendorizadas independientes del encoder bajo prueba:
// UPNG.js (PNG -> píxeles) y jsQR (píxeles -> texto del código).
//
// Ejecutar: node --test src/test/js/

import { test } from "node:test";
import assert from "node:assert/strict";
import qrcode from "../../main/resources/static/vendor/qrcode.js";
import QrPng from "../../main/resources/static/qr-code.js";
import { decodePngToRgba, decodeQrText } from "./png-decode.mjs";

const SHORT_URL = "http://192.168.50.10:8080/7";
const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

test("el PNG generado decodifica exactamente el enlace acortado devuelto por la API", () => {
    const png = QrPng.encode(qrcode, SHORT_URL);

    const { rgba, width, height } = decodePngToRgba(png);
    assert.equal(decodeQrText(rgba, width, height), SHORT_URL);
});

test("cada enlace devuelto produce un PNG con su propio contenido", () => {
    const otroEnlace = "http://192.168.50.10:8080/zz9";

    const { rgba, width, height } = decodePngToRgba(QrPng.encode(qrcode, otroEnlace));
    const texto = decodeQrText(rgba, width, height);

    assert.equal(texto, otroEnlace);
    assert.notEqual(texto, SHORT_URL);
});

test("la salida es un PNG cuadrado con firma válida, zona de silencio y tamaño escaneable", () => {
    const png = QrPng.encode(qrcode, SHORT_URL);

    assert.deepEqual([...png.subarray(0, 8)], PNG_SIGNATURE);

    const { width, height } = decodePngToRgba(png);
    const expected = (QrPng.matrix(qrcode, SHORT_URL).size + 2 * QrPng.QUIET_MODULES) * QrPng.SCALE_PX;
    assert.equal(width, expected);
    assert.equal(height, expected);
    assert.ok(width >= 128, `imagen demasiado chica para escanear: ${width}px`);
});

test("los píxeles decodificados reproducen la matriz QR y su zona de silencio", () => {
    const png = QrPng.encode(qrcode, SHORT_URL);
    const { rgba, width } = decodePngToRgba(png);
    const { size, isDark } = QrPng.matrix(qrcode, SHORT_URL);

    const pixel = (x, y) => rgba[(y * width + x) * 4];
    const quietOffset = QrPng.QUIET_MODULES * QrPng.SCALE_PX;
    const halfModule = Math.floor(QrPng.SCALE_PX / 2);

    for (let row = 0; row < size; row++) {
        for (let col = 0; col < size; col++) {
            const x = quietOffset + col * QrPng.SCALE_PX + halfModule;
            const y = quietOffset + row * QrPng.SCALE_PX + halfModule;
            assert.equal(
                pixel(x, y) < 128, isDark(col, row),
                `módulo (${col}, ${row}) con brillo incorrecto`);
        }
    }

    // Zona de silencio: el borde superior completo debe ser claro.
    for (let x = 0; x < width; x++) {
        assert.ok(pixel(x, 1) >= 128, `zona de silencio oscura en x=${x}`);
    }
});

test("el nombre de archivo sugerido identifica el enlace por su alias", () => {
    assert.equal(QrPng.suggestedFileName("7"), "qr-7.png");
    assert.equal(QrPng.suggestedFileName("aB3"), "qr-aB3.png");
});
