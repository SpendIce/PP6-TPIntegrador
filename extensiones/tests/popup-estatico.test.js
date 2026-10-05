"use strict";

/*
 * popup.js no se puede ejecutar en node (usa APIs del navegador y DOM),
 * pero el cableado entre el HTML y el JS si se puede verificar
 * estaticamente: los ids que bindElements busca deben existir en
 * popup.html, y los scripts referenciados deben estar en el orden que
 * requieren las dependencias entre modulos.
 */

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.join(__dirname, "..");

const html = fs.readFileSync(path.join(ROOT, "compartido", "popup.html"), "utf8");
const popupJs = fs.readFileSync(path.join(ROOT, "compartido", "popup.js"), "utf8");

test("cada id que bindElements pide existe en popup.html", () => {
  const block = popupJs.match(/for \(const id of \[([\s\S]*?)\]\)/);
  assert.ok(block, "bindElements debe iterar una lista literal de ids");
  const ids = [...block[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]);
  assert.ok(ids.length >= 10, "lista de ids sospechosamente corta");
  for (const id of ids) {
    assert.ok(html.includes(`id="${id}"`), `popup.html: falta id="${id}"`);
  }
});

test("los scripts se cargan antes que popup.js y en orden de dependencias", () => {
  const scripts = [...html.matchAll(/src="([^"]+)"/g)].map((m) => m[1]);
  const position = (name) => scripts.indexOf(name);
  assert.ok(position("vendor/qrcodegen.js") >= 0, "falta el vendor QR");
  assert.ok(position("api.js") < position("popup.js"), "api.js antes de popup.js");
  assert.ok(position("png.js") < position("qr.js"), "png.js antes de qr.js");
  assert.ok(position("qr.js") < position("popup.js"), "qr.js antes de popup.js");
  assert.ok(position("vendor/qrcodegen.js") < position("qr.js"), "vendor antes de qr.js");
});

test("los handlers del popup usan APIs disponibles en ambos navegadores", () => {
  // Convencion del proyecto: popup.js usa `ext` (browser/chrome) con
  // promesas. Nada de callbacks especificos de un vendor ni APIs no
  // declaradas en los manifiestos.
  for (const api of ["ext.tabs.query", "ext.storage.local", "ext.downloads.download"]) {
    assert.ok(popupJs.includes(api), `popup.js deberia usar ${api}`);
  }
  assert.ok(!popupJs.includes("chrome.") || popupJs.includes("typeof browser"), "namespace resuelto cross-browser");
});
