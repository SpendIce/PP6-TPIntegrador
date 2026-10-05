"use strict";

/*
 * Puente CommonJS para la libreria vendorizada qrcodegen.js.
 *
 * qrcodegen.js es un archivo de navegador: define el simbolo global
 * `qrcodegen` con `var` en el nivel superior y no exporta nada. En el
 * navegador se carga con <script src> y queda en globalThis. Para los
 * tests de node se ejecuta el mismo archivo dentro de una funcion y se
 * captura el simbolo, sin modificar el vendor.
 */

const fs = require("node:fs");
const path = require("node:path");

const source = fs.readFileSync(path.join(__dirname, "qrcodegen.js"), "utf8");
const load = new Function(`${source}\n;return qrcodegen;`);

module.exports = load();
