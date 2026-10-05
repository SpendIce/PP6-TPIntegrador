"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.join(__dirname, "..");

function manifest(browser) {
  const file = path.join(ROOT, browser, "manifest.json");
  return JSON.parse(fs.readFileSync(file, "utf8"));
}

test("ambos manifiestos son JSON valido con nombre, version y descripcion", () => {
  for (const browser of ["chrome", "firefox"]) {
    const m = manifest(browser);
    assert.ok(m.name, `${browser}: name`);
    assert.match(m.version, /^\d+\.\d+\.\d+$/, `${browser}: version semver`);
    assert.ok(m.description, `${browser}: description`);
  }
});

test("el manifiesto de Chrome es MV3 con popup de accion", () => {
  const m = manifest("chrome");
  assert.equal(m.manifest_version, 3);
  assert.equal(m.action.default_popup, "popup.html");
  assert.ok(fs.existsSync(path.join(ROOT, "chrome", m.action.default_popup)));
});

test("el manifiesto de Firefox es MV2 instalable manualmente", () => {
  // MV2: los permisos de host en `permissions` se otorgan al instalar, sin
  // el otorgamiento en runtime que MV3 exige en Firefox.
  const m = manifest("firefox");
  assert.equal(m.manifest_version, 2);
  assert.equal(m.browser_action.default_popup, "popup.html");
  assert.ok(fs.existsSync(path.join(ROOT, "firefox", m.browser_action.default_popup)));
  assert.ok(m.browser_specific_settings.gecko.id, "id gecko para firma futura");
});

test("los permisos son los minimos justificados para la funcionalidad", () => {
  // activeTab: leer la URL de la pestaña al invocar la accion.
  // storage: persistir la direccion configurada de la API.
  // downloads: guardar el PNG del QR.
  const chrome = manifest("chrome");
  for (const perm of ["activeTab", "storage", "downloads"]) {
    assert.ok(chrome.permissions.includes(perm), `chrome permissions: ${perm}`);
  }
  assert.ok(!chrome.permissions.includes("tabs"), "chrome no pide tabs (activeTab alcanza)");

  const firefox = manifest("firefox");
  for (const perm of ["activeTab", "storage", "downloads"]) {
    assert.ok(firefox.permissions.includes(perm), `firefox permissions: ${perm}`);
  }
});

test("ambos navegadores tienen permiso de host http/https para llamar a la API configurable", () => {
  // La direccion de la API la configura el usuario para la demo LAN: no se
  // puede enumerar un origen puntual, se necesitan ambos esquemas. Con el
  // permiso de host, el fetch desde la pagina del popup no pasa por CORS.
  const chrome = manifest("chrome");
  assert.ok(chrome.host_permissions.includes("http://*/*"), "chrome http");
  assert.ok(chrome.host_permissions.includes("https://*/*"), "chrome https");

  const firefox = manifest("firefox");
  assert.ok(firefox.permissions.includes("http://*/*"), "firefox http en permissions (otorga al instalar)");
  assert.ok(firefox.permissions.includes("https://*/*"), "firefox https en permissions");
});

test("los archivos referenciados por el popup existen en cada paquete", () => {
  const html = fs.readFileSync(path.join(ROOT, "compartido", "popup.html"), "utf8");
  const scripts = [...html.matchAll(/src="([^"]+)"/g)].map((m) => m[1]);
  const styles = [...html.matchAll(/href="([^"]+)"/g)]
    .map((m) => m[1])
    .filter((ref) => !ref.startsWith("#") && !ref.includes("://"));
  assert.ok(scripts.length >= 4, "popup.html referencia los modulos compartidos");

  for (const browser of ["chrome", "firefox"]) {
    for (const ref of [...scripts, ...styles]) {
      assert.ok(
        fs.existsSync(path.join(ROOT, browser, ref)),
        `${browser}: falta ${ref}`
      );
    }
  }
});
