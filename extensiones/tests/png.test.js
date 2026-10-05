"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const zlib = require("node:zlib");

const PngEncoder = require("../compartido/png.js");

const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

function parseChunks(png) {
  const chunks = [];
  let offset = PNG_SIGNATURE.length;
  while (offset < png.length) {
    const length = png.readUInt32BE(offset);
    const type = png.subarray(offset + 4, offset + 8).toString("latin1");
    const data = png.subarray(offset + 8, offset + 8 + length);
    const crc = png.readUInt32BE(offset + 8 + length);
    chunks.push({ type, data, crc });
    offset += 12 + length;
  }
  return chunks;
}

function solidRgba(width, height, [r, g, b, a]) {
  const rgba = new Uint8Array(width * height * 4);
  for (let i = 0; i < width * height; i++) {
    rgba.set([r, g, b, a], i * 4);
  }
  return rgba;
}

test("encodePng produce la firma PNG y termina en IEND", () => {
  const png = Buffer.from(PngEncoder.encodePng(1, 1, solidRgba(1, 1, [0, 0, 0, 255])));
  assert.deepEqual([...png.subarray(0, 8)], PNG_SIGNATURE);

  const chunks = parseChunks(png);
  assert.equal(chunks.at(-1).type, "IEND");
  assert.equal(chunks.at(-1).data.length, 0);
});

test("IHDR declara dimensiones y formato RGBA de 8 bits sin entrelazado", () => {
  const png = Buffer.from(PngEncoder.encodePng(3, 2, solidRgba(3, 2, [255, 0, 0, 255])));
  const ihdr = parseChunks(png)[0];
  assert.equal(ihdr.type, "IHDR");
  assert.equal(ihdr.data.readUInt32BE(0), 3, "width");
  assert.equal(ihdr.data.readUInt32BE(4), 2, "height");
  assert.equal(ihdr.data[8], 8, "bit depth");
  assert.equal(ihdr.data[9], 6, "color type RGBA");
  assert.equal(ihdr.data[10], 0, "compression");
  assert.equal(ihdr.data[11], 0, "filter");
  assert.equal(ihdr.data[12], 0, "interlace");
});

test("cada chunk lleva el CRC32 correcto segun el estandar", () => {
  const rgba = solidRgba(4, 4, [9, 8, 7, 255]);
  const png = Buffer.from(PngEncoder.encodePng(4, 4, rgba));
  for (const { type, data, crc } of parseChunks(png)) {
    const expected = zlib.crc32(Buffer.concat([Buffer.from(type, "latin1"), data]));
    assert.equal(crc, expected, `CRC del chunk ${type}`);
  }
});

test("el IDAT descomprime a scanlines con filtro 0 y los pixeles originales", () => {
  // Imagen 2x2: rojo, verde / azul, blanco.
  const rgba = new Uint8Array([
    255, 0, 0, 255, 0, 255, 0, 255,
    0, 0, 255, 255, 255, 255, 255, 255
  ]);
  const png = Buffer.from(PngEncoder.encodePng(2, 2, rgba));
  const idat = Buffer.concat(
    parseChunks(png).filter((c) => c.type === "IDAT").map((c) => c.data)
  );
  const raw = zlib.inflateSync(idat);
  assert.equal(raw.length, 2 * (1 + 2 * 4));
  assert.deepEqual(
    [...raw],
    [0, 255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 0, 255, 255, 255, 255, 255, 255]
  );
});

test("imagenes grandes usan varios bloques deflate almacenados", () => {
  // 300x300 RGBA + filtros > 65535 bytes por bloque: ejercita el split.
  const width = 300;
  const height = 300;
  const png = Buffer.from(PngEncoder.encodePng(width, height, solidRgba(width, height, [1, 2, 3, 255])));
  const idat = Buffer.concat(
    parseChunks(png).filter((c) => c.type === "IDAT").map((c) => c.data)
  );
  const raw = zlib.inflateSync(idat);
  assert.equal(raw.length, height * (1 + width * 4));
});

test("encodePng rechaza argumentos invalidos", () => {
  assert.throws(() => PngEncoder.encodePng(0, 1, new Uint8Array(4)));
  assert.throws(() => PngEncoder.encodePng(1, -2, new Uint8Array(4)));
  assert.throws(() => PngEncoder.encodePng(2, 2, new Uint8Array(4)), /rgba|length|p[ií]xeles/i);
});

test("crc32 coincide con zlib.crc32 para entradas conocidas", () => {
  const cases = [new Uint8Array(0), Buffer.from("IEND"), Buffer.from("acortador de enlaces")];
  for (const bytes of cases) {
    assert.equal(PngEncoder.crc32(bytes), zlib.crc32(Buffer.from(bytes)));
  }
  assert.equal(PngEncoder.crc32(Buffer.from("IEND")), 0xae426082, "vector de prueba del RFC 2083");
});

test("adler32 coincide con el vector del RFC 1950", () => {
  assert.equal(PngEncoder.adler32(Buffer.from("Wikipedia")), 0x11e60398);
});
