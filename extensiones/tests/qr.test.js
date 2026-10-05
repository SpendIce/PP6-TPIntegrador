"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const zlib = require("node:zlib");

const QrImage = require("../compartido/qr.js");
const qrcodegen = require("../compartido/vendor/qrcodegen.cjs");

const SHORT_URL = "http://192.168.1.50:8080/7";

/* Decodificador minimo de PNG para inspeccionar pixeles en las pruebas. */
function decodePixels(pngBytes) {
  const png = Buffer.from(pngBytes);
  let offset = 8;
  let width = 0;
  let height = 0;
  const idat = [];
  while (offset < png.length) {
    const length = png.readUInt32BE(offset);
    const type = png.subarray(offset + 4, offset + 8).toString("latin1");
    const data = png.subarray(offset + 8, offset + 8 + length);
    if (type === "IHDR") {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
    } else if (type === "IDAT") {
      idat.push(data);
    }
    offset += 12 + length;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const rgba = new Uint8Array(width * height * 4);
  const stride = width * 4;
  for (let y = 0; y < height; y++) {
    assert.equal(raw[y * (stride + 1)], 0, "filtro de scanline");
    rgba.set(raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)), y * stride);
  }
  return { width, height, rgba };
}

function pixelAt(image, x, y) {
  const i = (y * image.width + x) * 4;
  return [...image.rgba.subarray(i, i + 4)];
}

const BLACK = [0, 0, 0, 255];
const WHITE = [255, 255, 255, 255];

test("encodeQrMatrix devuelve una matriz cuadrada con patron finder oscuro", () => {
  const matrix = QrImage.encodeQrMatrix(SHORT_URL);
  assert.ok(matrix.size >= 21);
  assert.equal(typeof matrix.isDark, "function");
  assert.equal(matrix.isDark(0, 0), true, "esquina del patron finder superior izquierdo");
  assert.equal(matrix.isDark(matrix.size - 1, 0), true, "patron finder superior derecho");
});

test("renderQrPngBytes produce un PNG con dimensiones (size + 2*borde) * escala", () => {
  const scale = 4;
  const border = 2;
  const png = QrImage.renderQrPngBytes(SHORT_URL, { scale, border });
  const image = decodePixels(png);

  const expected = qrcodegen.QrCode.encodeText(SHORT_URL, qrcodegen.QrCode.Ecc.MEDIUM).size;
  assert.equal(image.width, (expected + border * 2) * scale);
  assert.equal(image.height, image.width);
});

test("la zona de silencio es blanca y el primer modulo coincide con la matriz", () => {
  const scale = 3;
  const border = 4;
  const matrix = QrImage.encodeQrMatrix(SHORT_URL);
  const image = decodePixels(QrImage.renderQrPngBytes(SHORT_URL, { scale, border }));

  assert.deepEqual(pixelAt(image, 0, 0), WHITE, "zona de silencio");

  // Centro del primer modulo: coincide con isDark(0,0) de la matriz.
  const modulePixel = pixelAt(image, border * scale + Math.floor(scale / 2), border * scale + Math.floor(scale / 2));
  assert.deepEqual(modulePixel, matrix.isDark(0, 0) ? BLACK : WHITE);
});

test("el PNG es deterministico para el mismo texto y difiere entre textos", () => {
  const a = QrImage.renderQrPngBytes(SHORT_URL);
  const b = QrImage.renderQrPngBytes(SHORT_URL);
  const c = QrImage.renderQrPngBytes("http://192.168.1.50:8080/8");
  assert.deepEqual([...a], [...b], "mismo texto, mismos bytes");
  assert.notDeepEqual([...a], [...c], "textos distintos, PNG distinto");
});

test("pngDataUrl devuelve un data URL cuyo contenido es el PNG del QR", () => {
  const dataUrl = QrImage.pngDataUrl(SHORT_URL);
  assert.ok(dataUrl.startsWith("data:image/png;base64,"));
  const decoded = Buffer.from(dataUrl.slice("data:image/png;base64,".length), "base64");
  assert.deepEqual([...decoded], [...QrImage.renderQrPngBytes(SHORT_URL)]);
});

test("acepta URLs largas dentro de la capacidad del QR", () => {
  const longUrl = `https://ejemplo.com/${"a".repeat(400)}`;
  const png = QrImage.renderQrPngBytes(longUrl);
  const image = decodePixels(png);
  assert.ok(image.width > 0);
});

test("encodeQrMatrix rechaza textos vacios", () => {
  assert.throws(() => QrImage.encodeQrMatrix(""));
});
