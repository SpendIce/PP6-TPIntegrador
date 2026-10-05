/*
 * PngEncoder — codificador PNG minimo en JavaScript puro.
 *
 * Produce PNG RGBA de 8 bits por canal, sin entrelazado, con scanlines de
 * filtro "None" y un flujo zlib/deflate de bloques almacenados (stored).
 * No usa canvas ni dependencias: funciona igual en la pagina del popup y
 * en las pruebas de node.
 *
 * En el navegador queda como global `PngEncoder`; en node se exporta por
 * CommonJS para `node --test`.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) {
    module.exports = api;
  }
  root.PngEncoder = api;
})(typeof globalThis === "object" ? globalThis : this, function () {
  "use strict";

  const PNG_SIGNATURE = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const EMPTY = new Uint8Array(0);
  const STORED_BLOCK_MAX = 65535;
  const ADLER_MOD = 65521;

  /* CRC-32 (polinomio 0xEDB88320) segun PNG/RFC 2083 y zlib/RFC 1950. */
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

  /* Adler-32 segun RFC 1950, para cerrar el flujo zlib. */
  function adler32(bytes) {
    let a = 1;
    let b = 0;
    for (let i = 0; i < bytes.length; i++) {
      a = (a + bytes[i]) % ADLER_MOD;
      b = (b + a) % ADLER_MOD;
    }
    return ((b << 16) | a) >>> 0;
  }

  /* Envuelve datos crudos en un flujo zlib con bloques deflate almacenados. */
  function zlibStore(data) {
    const blockCount = Math.max(1, Math.ceil(data.length / STORED_BLOCK_MAX));
    const out = new Uint8Array(2 + blockCount * 5 + data.length + 4);
    out[0] = 0x78;
    out[1] = 0x01;

    let pos = 2;
    let offset = 0;
    do {
      const len = Math.min(STORED_BLOCK_MAX, data.length - offset);
      const last = offset + len === data.length;
      out[pos++] = last ? 0x01 : 0x00;
      out[pos++] = len & 0xff;
      out[pos++] = (len >>> 8) & 0xff;
      const nlen = (~len) & 0xffff;
      out[pos++] = nlen & 0xff;
      out[pos++] = (nlen >>> 8) & 0xff;
      out.set(data.subarray(offset, offset + len), pos);
      pos += len;
      offset += len;
    } while (offset < data.length);

    const sum = adler32(data);
    out[pos++] = (sum >>> 24) & 0xff;
    out[pos++] = (sum >>> 16) & 0xff;
    out[pos++] = (sum >>> 8) & 0xff;
    out[pos++] = sum & 0xff;
    return out.subarray(0, pos);
  }

  function chunk(type, data) {
    const out = new Uint8Array(12 + data.length);
    const view = new DataView(out.buffer);
    view.setUint32(0, data.length);
    for (let i = 0; i < 4; i++) {
      out[4 + i] = type.charCodeAt(i);
    }
    out.set(data, 8);
    view.setUint32(8 + data.length, crc32(out.subarray(4, 8 + data.length)));
    return out;
  }

  /*
   * encodePng(width, height, rgba) -> Uint8Array
   * `rgba` debe tener width*height*4 valores 0..255 en orden de lectura.
   */
  function encodePng(width, height, rgba) {
    if (!Number.isInteger(width) || !Number.isInteger(height) || width <= 0 || height <= 0) {
      throw new RangeError("width y height deben ser enteros positivos");
    }
    if (!rgba || rgba.length !== width * height * 4) {
      throw new RangeError("rgba debe tener width*height*4 elementos");
    }
    const pixels = rgba instanceof Uint8Array ? rgba : Uint8Array.from(rgba);

    const raw = new Uint8Array(height * (1 + width * 4));
    for (let y = 0; y < height; y++) {
      const rowStart = y * (1 + width * 4);
      raw[rowStart] = 0;
      raw.set(pixels.subarray(y * width * 4, (y + 1) * width * 4), rowStart + 1);
    }

    const ihdr = new Uint8Array(13);
    const header = new DataView(ihdr.buffer);
    header.setUint32(0, width);
    header.setUint32(4, height);
    ihdr[8] = 8;
    ihdr[9] = 6;
    ihdr[10] = 0;
    ihdr[11] = 0;
    ihdr[12] = 0;

    const parts = [
      PNG_SIGNATURE,
      chunk("IHDR", ihdr),
      chunk("IDAT", zlibStore(raw)),
      chunk("IEND", EMPTY)
    ];
    const total = parts.reduce((n, part) => n + part.length, 0);
    const png = new Uint8Array(total);
    let pos = 0;
    for (const part of parts) {
      png.set(part, pos);
      pos += part.length;
    }
    return png;
  }

  return { encodePng, crc32, adler32 };
});
