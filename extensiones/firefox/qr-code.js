/*
 * QR del enlace acortado (issue #7): convierte la matriz QR que produce
 * la biblioteca vendorizada `vendor/qrcode.js` en un archivo PNG en
 * memoria, sin canvas ni servicios externos. El PNG codifica siempre el
 * `shortUrl` devuelto por la API (la dirección pública del enlace, no la
 * URL de destino ni una asignación histórica: ver CONTEXT.md, «Código QR
 * del enlace»).
 *
 * Expone `QrPng` como global en el navegador y como `module.exports`
 * bajo CommonJS para que las pruebas de Node (`src/test/js/`) generen y
 * decodifiquen los mismos bytes que descarga el usuario.
 */
const QrPng = (function () {
    "use strict";

    // Escala y zona de silencio. 8 px por módulo con 4 módulos de borde
    // produce imágenes de ~230-300 px para los enlaces del servicio,
    // escaneables desde pantalla y desde el PNG descargado.
    const SCALE_PX = 8;
    const QUIET_MODULES = 4;
    const ERROR_CORRECTION = "M";

    // Matriz del código QR para `text`: la biblioteca elige la versión
    // mínima (typeNumber 0). isDark(x, y) toma coordenadas columna/fila.
    function matrix(qrcodeFactory, text) {
        const qr = qrcodeFactory(0, ERROR_CORRECTION);
        qr.addData(text);
        qr.make();
        const size = qr.getModuleCount();
        return {
            size: size,
            isDark: (x, y) => qr.isDark(y, x)
        };
    }

    // PNG de escala de grises 8 bits: fondo claro, módulos oscuros.
    function encode(qrcodeFactory, text) {
        const { size, isDark } = matrix(qrcodeFactory, text);
        const side = (size + 2 * QUIET_MODULES) * SCALE_PX;
        const gray = new Uint8Array(side * side).fill(255);

        for (let y = 0; y < size; y++) {
            for (let x = 0; x < size; x++) {
                if (isDark(x, y)) {
                    fillModule(gray, side,
                        (QUIET_MODULES + x) * SCALE_PX,
                        (QUIET_MODULES + y) * SCALE_PX);
                }
            }
        }
        return encodePng(side, side, gray);
    }

    // Nombre razonable para la descarga: identifica al enlace por su
    // alias público (alfabeto de 58 símbolos, seguro como nombre).
    function suggestedFileName(alias) {
        const safe = String(alias).replace(/[^A-Za-z0-9_-]/g, "");
        return `qr-${safe}.png`;
    }

    function fillModule(gray, side, x0, y0) {
        for (let y = y0; y < y0 + SCALE_PX; y++) {
            gray.fill(0, y * side + x0, y * side + x0 + SCALE_PX);
        }
    }

    // ------------------------------------------------------------------
    // PNG mínimo: firma + IHDR (grises 8 bits, sin entrelazar) + IDAT
    // (zlib con bloques deflate almacenados, sin compresión) + IEND.
    // Las pruebas decodifican esta salida con UPNG.js, por lo que la
    // corrección del formato queda verificada de punta a punta.
    // ------------------------------------------------------------------

    const PNG_SIGNATURE = Uint8Array.of(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);

    function encodePng(width, height, gray) {
        const raw = new Uint8Array(height * (width + 1));
        for (let y = 0; y < height; y++) {
            raw[y * (width + 1)] = 0; // filtro de scanline: ninguno
            raw.set(gray.subarray(y * width, (y + 1) * width), y * (width + 1) + 1);
        }

        const ihdr = concat(
            u32be(width), u32be(height),
            Uint8Array.of(8, 0, 0, 0, 0)); // 8 bits, grises, deflate, filtros, sin entrelazar

        return concat(
            PNG_SIGNATURE,
            chunk("IHDR", ihdr),
            chunk("IDAT", zlibStore(raw)),
            chunk("IEND", new Uint8Array(0)));
    }

    // Envoltura zlib RFC 1950 con bloques deflate "stored" (RFC 1951):
    // sin compresión, pero 100 % conforme al formato que espera cualquier
    // lector PNG. Los bloques aceptan hasta 65535 bytes.
    function zlibStore(data) {
        const MAX_BLOCK = 0xffff;
        const blockCount = Math.max(1, Math.ceil(data.length / MAX_BLOCK));
        const out = new Uint8Array(2 + blockCount * 5 + data.length + 4);
        let o = 0;
        out[o++] = 0x78; // CMF: deflate, ventana 32K
        out[o++] = 0x01; // FLG: (0x7801 divisible por 31), nivel rápido

        for (let i = 0; i < blockCount; i++) {
            const len = Math.min(MAX_BLOCK, data.length - i * MAX_BLOCK);
            out[o++] = i === blockCount - 1 ? 0x01 : 0x00; // BFINAL + BTYPE=stored
            out[o++] = len & 0xff;
            out[o++] = (len >> 8) & 0xff;
            out[o++] = ~len & 0xff; // NLEN = complemento a 1 de LEN
            out[o++] = (~len >> 8) & 0xff;
            out.set(data.subarray(i * MAX_BLOCK, i * MAX_BLOCK + len), o);
            o += len;
        }

        const checksum = adler32(data);
        out[o++] = (checksum >>> 24) & 0xff;
        out[o++] = (checksum >>> 16) & 0xff;
        out[o++] = (checksum >>> 8) & 0xff;
        out[o++] = checksum & 0xff;
        return out;
    }

    function adler32(data) {
        let a = 1;
        let b = 0;
        for (let i = 0; i < data.length; i++) {
            a = (a + data[i]) % 65521;
            b = (b + a) % 65521;
        }
        return ((b << 16) | a) >>> 0;
    }

    const CRC_TABLE = (function () {
        const table = new Uint32Array(256);
        for (let n = 0; n < 256; n++) {
            let c = n;
            for (let k = 0; k < 8; k++) {
                c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
            }
            table[n] = c >>> 0;
        }
        return table;
    })();

    function crc32(bytes) {
        let c = 0xffffffff;
        for (let i = 0; i < bytes.length; i++) {
            c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
        }
        return (c ^ 0xffffffff) >>> 0;
    }

    function chunk(type, data) {
        const name = Uint8Array.of(
            type.charCodeAt(0), type.charCodeAt(1), type.charCodeAt(2), type.charCodeAt(3));
        const out = new Uint8Array(12 + data.length);
        out.set(u32be(data.length), 0);
        out.set(name, 4);
        out.set(data, 8);
        out.set(u32be(crc32(concat(name, data))), 8 + data.length);
        return out;
    }

    function u32be(value) {
        return Uint8Array.of(
            (value >>> 24) & 0xff, (value >>> 16) & 0xff,
            (value >>> 8) & 0xff, value & 0xff);
    }

    function concat(...arrays) {
        const total = arrays.reduce((sum, a) => sum + a.length, 0);
        const out = new Uint8Array(total);
        let offset = 0;
        for (const a of arrays) {
            out.set(a, offset);
            offset += a.length;
        }
        return out;
    }

    return {
        matrix: matrix,
        encode: encode,
        suggestedFileName: suggestedFileName,
        SCALE_PX: SCALE_PX,
        QUIET_MODULES: QUIET_MODULES
    };
})();

if (typeof module !== "undefined" && module.exports) {
    module.exports = QrPng;
}
