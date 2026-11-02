"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const IDS = [
  "status", "result", "destination", "short-url", "expires", "reuse-notice",
  "qr", "download-png", "again", "error", "error-code", "error-message",
  "retry", "api-base", "save-config", "config-status",
  "account", "account-email", "account-namespace", "namespace-notice",
  "alias", "create-link", "logout", "login", "login-button"
];

function deferred() {
  let resolve;
  const promise = new Promise((r) => { resolve = r; });
  return { promise, resolve };
}

// Ejecuta popup.js con DOM, storage y LinkApi simulados (sin navegador).
async function cargarPopup(linkApi) {
  const el = {};
  const handlers = {};
  for (const id of IDS) {
    el[id] = {
      hidden: false, textContent: "", value: "", disabled: false,
      addEventListener(ev, fn) { handlers[`${id}:${ev}`] = fn; }
    };
  }
  let apiBase = "https://api-a.example";
  const ext = {
    storage: { local: {
      get: async () => ({ apiBase }),
      set: async (o) => { apiBase = o.apiBase; }
    } },
    tabs: { query: async () => [{ url: "https://destino.example/" }], create: async () => {} },
    downloads: { download: async () => {} }
  };
  const sandbox = {
    chrome: ext, document: { readyState: "complete", getElementById: (id) => el[id] },
    LinkApi: linkApi, QrPng: { encode: () => new Uint8Array(1), suggestedFileName: () => "x.png" },
    qrcode: {}, URL: { createObjectURL: () => "blob:x", revokeObjectURL() {} }, Blob: class {}
  };
  const file = path.join(__dirname, "..", "compartido", "popup.js");
  vm.runInNewContext(fs.readFileSync(file, "utf8"), sandbox);
  const tick = () => new Promise((r) => setImmediate(r));
  await tick();
  return { el, handlers, tick };
}

function apiBase(overrides) {
  return Object.assign({
    ApiError: class extends Error {},
    resolveApiBase: (v) => v,
    normalizeApiBase: (v) => v,
    DEFAULT_API_BASE: "https://api-a.example",
    isShortenableTabUrl: () => true,
    formatExpiresAt: () => ({ local: "ya", minutesLeft: 5 }),
    buildLoginUrl: (b) => `${b}/login`
  }, overrides);
}

const ana = { email: "ana@example.com", namespace: "ana" };

test("al guardar otra API se descarta la cuenta y se consulta su sesion", async () => {
  const consultas = [];
  const api = apiBase({
    getSession: async (base) => { consultas.push(base); return base.includes("api-a") ? ana : null; },
    createLink: async () => { throw new Error("no debe crear"); }
  });
  const { el, handlers, tick } = await cargarPopup(api);
  assert.equal(el.account.hidden, false);
  el["api-base"].value = "https://api-b.example";
  await handlers["save-config:click"]();
  await tick();
  assert.deepEqual(consultas, ["https://api-a.example", "https://api-b.example"]);
  assert.equal(el.account.hidden, true);
});

test("la creacion en vuelo no pisa el resultado posterior al cierre de sesion", async () => {
  const pendientes = [];
  const api = apiBase({
    getSession: async () => ana,
    logout: async () => {},
    createLink: async (base, url, _x, alias) => {
      const d = deferred();
      pendientes.push({ alias, d });
      return d.promise;
    }
  });
  const { el, handlers, tick } = await cargarPopup(api);
  el.alias.value = "doc";
  handlers["create-link:click"]();
  await tick();
  handlers["logout:click"]();
  await tick();
  assert.equal(pendientes.length, 2);
  const link = (s) => ({ shortUrl: s, expiresAt: "", reuseNotice: "", alias: "a" });
  pendientes[1].d.resolve(link("https://api-a.example/123"));
  await tick();
  pendientes[0].d.resolve(link("https://api-a.example/ana/doc"));
  await tick();
  assert.equal(el["short-url"].textContent, "https://api-a.example/123");
});
