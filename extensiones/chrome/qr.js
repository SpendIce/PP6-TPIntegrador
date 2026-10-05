/*
 * QrImage — convierte el enlace acortado en un PNG escaneable.
 *
 * Envuelve la libreria vendorizada qrcodegen (matriz del QR) y el
 * codificador PNG propio (PngEncoder). No usa canvas, DOM ni red: la misma
 * imagen sirve para mostrar en el popup y para descargar como PNG.
 *
 * En el navegador queda como global `QrImage` (requiere los script tags de
 * vendor/qrcodegen.js y png.js cargados antes); en node se exporta por
 * CommonJS para `node --test`.
 */
(function (root, factory) {
  if (typeof module === "object" && module.exports) {
    module.exports = factory(require("./vendor/qrcodegen.cjs"), require("./png.js"));
  } else {
    root.QrImage = factory(root.qrcodegen, root.PngEncoder);
  }
})(typeof globalThis === "object" ? globalThis : this, function (qrcodegen, PngEncoder) {
  "use strict";

  const DEFAULTS = { scale: 8, border: 4 };
  const QR_ECC = "MEDIUM";
  const B64_CHUNK = 0x8000;

  /* encodeQrMatrix(texto) -> { size, isDark(x, y) } con la matriz del QR. */
  function encodeQrMatrix(text) {
    if (typeof text !== "string" || text.length === 0) {
      throw new TypeError("el contenido del QR no puede estar vacio");
    }
    const ecc = qrcodegen.QrCode.Ecc[QR_ECC] || qrcodegen.QrCode.Ecc.MEDIUM;
    const qr = qrcodegen.QrCode.encodeText(text, ecc);
    return {
      size: qr.size,
      isDark(x, y) {
        return qr.getModule(x, y);
      }
    };
  }

  /*
   * renderQrRgba(texto, {scale, border}) -> { width, height, rgba }
   * Cada modulo se dibuja scale x scale pixeles, con una zona de silencio
   * de `border` modulos (el estandar pide al menos 4).
   */
  function renderQrRgba(text, options) {
    const { scale, border } = Object.assign({}, DEFAULTS, options);
    if (!Number.isInteger(scale) || scale <= 0 || !Number.isInteger(border) || border < 0) {
      throw new RangeError("scale y border deben ser enteros no negativos");
    }
    const matrix = encodeQrMatrix(text);
    const width = (matrix.size + border * 2) * scale;
    const height = width;
    const rgba = new Uint8Array(width * height * 4);
    rgba.fill(255);

    for (let y = 0; y < matrix.size; y++) {
      for (let x = 0; x < matrix.size; x++) {
        if (!matrix.isDark(x, y)) {
          continue;
        }
        const startX = (x + border) * scale;
        const startY = (y + border) * scale;
        for (let dy = 0; dy < scale; dy++) {
          const rowStart = ((startY + dy) * width + startX) * 4;
          for (let dx = 0; dx < scale; dx++) {
            const i = rowStart + dx * 4;
            rgba[i] = 0;
            rgba[i + 1] = 0;
            rgba[i + 2] = 0;
            rgba[i + 3] = 255;
          }
        }
      }
    }
    return { width, height, rgba };
  }

  /* renderQrPngBytes(texto, opciones) -> Uint8Array con el PNG del QR. */
  function renderQrPngBytes(text, options) {
    const { width, height, rgba } = renderQrRgba(text, options);
    return PngEncoder.encodePng(width, height, rgba);
  }

  function bytesToBase64(bytes) {
    if (typeof Buffer === "function" && Buffer.from) {
      return Buffer.from(bytes).toString("base64");
    }
    let binary = "";
    for (let i = 0; i < bytes.length; i += B64_CHUNK) {
      binary += String.fromCharCode.apply(null, bytes.subarray(i, i + B64_CHUNK));
    }
    return btoa(binary);
  }

  /* pngDataUrl(texto, opciones) -> "data:image/png;base64,..." listo para <img> o descarga. */
  function pngDataUrl(text, options) {
    return `data:image/png;base64,${bytesToBase64(renderQrPngBytes(text, options))}`;
  }

  return {
    DEFAULTS,
    QR_ECC,
    encodeQrMatrix,
    renderQrRgba,
    renderQrPngBytes,
    pngDataUrl
  };
});
