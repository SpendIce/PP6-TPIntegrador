"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.join(__dirname, "..");

// Debe coincidir con la lista de sincronizar.sh.
const SHARED_FILES = [
  "popup.html",
  "popup.css",
  "popup.js",
  "api.js",
  "png.js",
  "qr.js",
  "vendor/qrcodegen.js"
];

test("chrome/ y firefox/ contienen copias identicas de compartido/", () => {
  for (const browser of ["chrome", "firefox"]) {
    for (const file of SHARED_FILES) {
      const shared = fs.readFileSync(path.join(ROOT, "compartido", file));
      const copyPath = path.join(ROOT, browser, file);
      assert.ok(fs.existsSync(copyPath), `${browser}: falta ${file} (correr sincronizar.sh)`);
      const copy = fs.readFileSync(copyPath);
      assert.deepEqual(copy, shared, `${browser}/${file} difiere de compartido/${file} (correr sincronizar.sh)`);
    }
  }
});

test("los paquetes no contienen archivos ajenos a la lista compartida mas el manifiesto", () => {
  const allowed = new Set([...SHARED_FILES, "manifest.json"]);
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
