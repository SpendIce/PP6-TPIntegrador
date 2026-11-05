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
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

// Ejecuta popup.js con DOM, storage y LinkApi simulados (sin navegador).
async function cargarPopup(linkApi, tabsQuery) {
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
    tabs: { query: tabsQuery || (async () => [{ url: "https://destino.example/" }]), create: async () => {} },
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
    createLink: async () => ({ shortUrl: "https://api-b.example/1", expiresAt: "", reuseNotice: "", alias: "a" })
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

const link = (s) => ({ shortUrl: s, expiresAt: "", reuseNotice: "", alias: "a" });

test("al cambiar a una API sin sesion se acorta de inmediato como en la etapa 1", async () => {
  const creados = [];
  const api = apiBase({
    getSession: async (base) => (base.includes("api-a") ? ana : null),
    createLink: async (base, url, _x, alias) => { creados.push({ base, alias }); return link("https://api-b.example/1"); }
  });
  const { el, handlers, tick } = await cargarPopup(api);
  assert.equal(creados.length, 0);
  el["api-base"].value = "https://api-b.example";
  await handlers["save-config:click"]();
  await tick();
  assert.deepEqual(creados, [{ base: "https://api-b.example", alias: "" }]);
  assert.equal(el.result.hidden, false);
  assert.equal(el.login.hidden, false);
});

test("el cierre de sesion de la API anterior no afecta a la nueva", async () => {
  const logouts = [];
  const creados = [];
  const api = apiBase({
    getSession: async (base) => (base.includes("api-a") ? ana : { email: "bea@example.com", namespace: "bea" }),
    logout: (base) => { const d = deferred(); logouts.push(d); return d.promise; },
    createLink: async (base) => { creados.push(base); return link("x"); }
  });
  const { el, handlers, tick } = await cargarPopup(api);
  handlers["logout:click"]();
  await tick();
  el["api-base"].value = "https://api-b.example";
  await handlers["save-config:click"]();
  await tick();
  assert.equal(el.account.hidden, false);
  logouts[0].resolve();
  await tick();
  assert.equal(el.account.hidden, false);
  assert.deepEqual(creados, []);
});

test("el rechazo tardio del cierre de sesion no muestra error en la nueva API", async () => {
  const logouts = [];
  const api = apiBase({
    getSession: async () => ana,
    logout: () => { const d = deferred(); logouts.push(d); return d.promise; }
  });
  const { el, handlers, tick } = await cargarPopup(api);
  handlers["logout:click"]();
  await tick();
  el["api-base"].value = "https://api-b.example";
  await handlers["save-config:click"]();
  await tick();
  el.error.hidden = true;
  logouts[0].reject(new Error("tarde"));
  await tick();
  assert.equal(el.error.hidden, true);
});

test("una creacion con la pestana pendiente no se ejecuta en la API nueva", async () => {
  const consultas = [];
  const creados = [];
  const api = apiBase({
    getSession: async (base) => (base.includes("api-a") ? ana : { email: "bea@example.com", namespace: "bea" }),
    createLink: async (base) => { creados.push(base); return link("x"); }
  });
  let primera = true;
  const d = deferred();
  const query = () => {
    if (primera) { primera = false; return Promise.resolve([{ url: "https://destino.example/" }]); }
    consultas.push(d);
    return d.promise;
  };
  const { el, handlers, tick } = await cargarPopup(api, query);
  // start no consulta pestana con sesion: la primera consulta es la del clic.
  primera = false;
  el.alias.value = "a-doc";
  handlers["create-link:click"]();
  await tick();
  el["api-base"].value = "https://api-b.example";
  await handlers["save-config:click"]();
  await tick();
  d.resolve([{ url: "https://destino.example/" }]);
  await tick();
  assert.deepEqual(creados, []);
  assert.equal(el.account.hidden, false);
});

test("una creacion anterior no reemplaza el resultado de la posterior", async () => {
  const pendientes = [];
  const api = apiBase({
    getSession: async () => ana,
    createLink: async () => { const d = deferred(); pendientes.push(d); return d.promise; }
  });
  const { el, handlers, tick } = await cargarPopup(api);
  el.alias.value = "primero";
  handlers["create-link:click"]();
  await tick();
  el.alias.value = "segundo";
  handlers["create-link:click"]();
  await tick();
  pendientes[1].resolve(link("https://api-a.example/ana/segundo"));
  await tick();
  pendientes[0].resolve(link("https://api-a.example/ana/primero"));
  await tick();
  assert.equal(el["short-url"].textContent, "https://api-a.example/ana/segundo");
});
