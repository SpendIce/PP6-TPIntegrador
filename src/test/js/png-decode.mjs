// Helpers para las pruebas del QR: decodifican los bytes PNG generados
// por `static/qr-code.js` usando bibliotecas vendorizadas probadas
// (UPNG.js para el contenedor PNG, jsQR para el contenido del código).
// `static/qr-code.js` es el código bajo prueba; este archivo no lo
// reimplementa: la decodificación es independiente del encoder.

import fs from "node:fs";
import zlib from "node:zlib";
import jsQR from "./vendor/jsQR.js";

const upng = loadUpng();

// UPNG.js está pensado para navegador o para Node con la dependencia
// `pako`; solo necesita `pako.inflate`. En vez de sumar pako se carga el
// archivo vendorizado con un shim mínimo sobre `node:zlib`, sin
// modificar el archivo original.
function loadUpng() {
    const source = fs.readFileSync(new URL("./vendor/UPNG.js", import.meta.url), "utf8");
    const module = { exports: {} };
    const pakoShim = {
        inflate: (data) => new Uint8Array(zlib.inflateSync(Buffer.from(data)))
    };
    const requireShim = (name) => {
        if (name !== "pako") {
            throw new Error(`UPNG pidió una dependencia inesperada: ${name}`);
        }
        return pakoShim;
    };
    new Function("module", "require", "window", source)(module, requireShim, {});
    return module.exports;
}

// Bytes PNG -> { width, height, rgba } con rgba en Uint8ClampedArray
// RGBA de 4 bytes por píxel, listo para jsQR.
export function decodePngToRgba(pngBytes) {
    const buffer = pngBytes.buffer.slice(
        pngBytes.byteOffset, pngBytes.byteOffset + pngBytes.byteLength);
    const image = upng.decode(buffer);
    const [rgba] = upng.toRGBA8(image);
    return { width: image.width, height: image.height, rgba: new Uint8ClampedArray(rgba) };
}

// Píxeles RGBA -> contenido textual del código QR, o null si no decodifica.
export function decodeQrText(rgba, width, height) {
    const result = jsQR(rgba, width, height);
    return result === null ? null : result.data;
}
