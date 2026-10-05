"use strict";

/*
 * Integracion ligera: un servidor node:http que responde como el backend
 * segun openapi.yaml (201 con el DTO completo; 400 con {error, message}),
 * y createLink ejercitado con el fetch real de node. Valida la solicitud
 * y el parseo de punta a punta sin levantar el servicio Java.
 */

const test = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");

const LinkApi = require("../compartido/api.js");

const VALID_NOTICE = "Este alias puede reutilizarse con otro destino después del vencimiento.";

function startStub(handler) {
  return new Promise((resolve) => {
    const server = http.createServer(handler);
    server.listen(0, "127.0.0.1", () => {
      const base = `http://127.0.0.1:${server.address().port}`;
      resolve({ server, base });
    });
  });
}

function createHandler() {
  const requests = [];
  const handler = (req, res) => {
    let body = "";
    req.on("data", (chunk) => { body += chunk; });
    req.on("end", () => {
      requests.push({ method: req.method, url: req.url, headers: req.headers, body });
      if (req.method === "POST" && req.url === "/api/links") {
        res.writeHead(201, { "Content-Type": "application/json", "Cache-Control": "no-store" });
        res.end(JSON.stringify({
          shortUrl: "http://192.168.1.50:8080/7",
          alias: "7",
          expiresAt: "2030-01-01T01:00:00Z",
          reuseNotice: "Este alias puede reutilizarse con otro destino después del vencimiento."
        }));
      } else {
        res.writeHead(404);
        res.end();
      }
    });
  };
  return { handler, requests };
}

test("createLink contra un servidor real envia el POST del contrato y devuelve el enlace", async (t) => {
  const { handler, requests } = createHandler();
  const { server, base } = await startStub(handler);
  t.after(() => server.close());

  const destination = "https://docs.ejemplo.com/carpeta/archivo?a=1&b=2#seccion";
  const link = await LinkApi.createLink(base, destination);

  assert.equal(requests.length, 1);
  assert.equal(requests[0].method, "POST");
  assert.equal(requests[0].url, "/api/links");
  assert.equal(requests[0].headers["content-type"], "application/json");
  assert.deepEqual(JSON.parse(requests[0].body), { destination });

  assert.equal(link.shortUrl, "http://192.168.1.50:8080/7");
  assert.equal(link.alias, "7");
  assert.equal(link.expiresAt, "2030-01-01T01:00:00Z");
  assert.equal(link.reuseNotice, VALID_NOTICE);
});

test("createLink contra un servidor real muestra el error 400 del contrato", async (t) => {
  const { server, base } = await startStub((req, res) => {
    res.writeHead(400, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "OWN_ORIGIN", message: "El destino pertenece al propio servicio." }));
  });
  t.after(() => server.close());

  await assert.rejects(
    LinkApi.createLink(base, "http://192.168.1.50:8080/x"),
    (e) => e.code === "OWN_ORIGIN" && e.message.includes("propio servicio") && e.httpStatus === 400
  );
});

test("createLink contra un puerto sin servicio reporta NETWORK_ERROR", async () => {
  // Puerto reservado y cerrado: la solicitud ni siquiera llega.
  await assert.rejects(
    LinkApi.createLink("http://127.0.0.1:1", "https://x.com"),
    (e) => e.code === "NETWORK_ERROR" && e.message.includes("127.0.0.1:1")
  );
});
