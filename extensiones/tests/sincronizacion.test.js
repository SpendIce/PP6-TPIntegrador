"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.join(__dirname, "..");
const STATIC = path.join(ROOT, "..", "src", "main", "resources", "static");

// Debe coincidir con las listas de sincronizar.sh.
// Archivos propios de la extension: la fuente es compartido/.
const SHARED_FILES = [
  "popup.html",
  "popup.css",
  "popup.js",
  "api.js"
];
// Stack QR compartido con la web (issue #7): la fuente es
// src/main/resources/static/, para que haya un solo vendor y un solo
// encoder PNG en todo el proyecto.
const WEB_FILES = [
  "qr-code.js",
  "vendor/qrcode.js",
  "vendor/qrcode-generator.LICENSE.txt"
];

test("chrome/ y firefox/ contienen copias identicas de sus fuentes", () => {
  for (const browser of ["chrome", "firefox"]) {
    for (const file of SHARED_FILES) {
      const source = fs.readFileSync(path.join(ROOT, "compartido", file));
      const copyPath = path.join(ROOT, browser, file);
      assert.ok(fs.existsSync(copyPath), `${browser}: falta ${file} (correr sincronizar.sh)`);
      assert.deepEqual(
        fs.readFileSync(copyPath), source,
        `${browser}/${file} difiere de compartido/${file} (correr sincronizar.sh)`
      );
    }
    for (const file of WEB_FILES) {
      const source = fs.readFileSync(path.join(STATIC, file));
      const copyPath = path.join(ROOT, browser, file);
      assert.ok(fs.existsSync(copyPath), `${browser}: falta ${file} (correr sincronizar.sh)`);
      assert.deepEqual(
        fs.readFileSync(copyPath), source,
        `${browser}/${file} difiere de static/${file} (correr sincronizar.sh)`
      );
    }
  }
});

test("los paquetes no contienen archivos ajenos a las fuentes mas el manifiesto", () => {
  const allowed = new Set([...SHARED_FILES, ...WEB_FILES, "manifest.json"]);
  for (const browser of ["chrome", "firefox"]) {
    const files = [];
    const walk = (dir) => {
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const rel = path.relative(path.join(ROOT, browser), path.join(dir, entry.name));
        if (entry.isDirectory()) {
          walk(path.join(dir, entry.name));
        } else {
          files.push(rel);
        }
      }
    };
    walk(path.join(ROOT, browser));
    for (const file of files) {
      assert.ok(allowed.has(file.split(path.sep).join("/")), `${browser}: archivo inesperado ${file}`);
    }
  }
});

test("la licencia del QR vendorizado viaja dentro de cada paquete", () => {
  for (const browser of ["chrome", "firefox"]) {
    const license = fs.readFileSync(path.join(ROOT, browser, "vendor", "qrcode-generator.LICENSE.txt"), "utf8");
    assert.match(license, /MIT/i, `${browser}: la licencia no parece MIT`);
  }
});
