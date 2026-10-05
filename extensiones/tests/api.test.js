"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

const LinkApi = require("../compartido/api.js");

const VALID_LINK = {
  shortUrl: "http://192.168.1.50:8080/7",
  alias: "7",
  expiresAt: "2030-01-01T01:00:00Z",
  reuseNotice: "Este alias puede reutilizarse con otro destino después del vencimiento."
};

function fakeResponse(status, payload, { failJson = false } = {}) {
  return {
    status,
    json: () => (failJson ? Promise.reject(new SyntaxError("invalid json")) : Promise.resolve(payload))
  };
}

test("resolveApiBase usa el valor por defecto ante configuracion vacia", () => {
  assert.equal(LinkApi.resolveApiBase(undefined), "http://localhost:8080");
  assert.equal(LinkApi.resolveApiBase(null), "http://localhost:8080");
  assert.equal(LinkApi.resolveApiBase(""), "http://localhost:8080");
  assert.equal(LinkApi.resolveApiBase("   "), "http://localhost:8080");
});

test("resolveApiBase conserva el valor configurado", () => {
  assert.equal(LinkApi.resolveApiBase("http://192.168.1.50:8080"), "http://192.168.1.50:8080");
});

test("normalizeApiBase acepta origenes http/https y recorta barras finales", () => {
  assert.equal(LinkApi.normalizeApiBase("http://localhost:8080"), "http://localhost:8080");
  assert.equal(LinkApi.normalizeApiBase("http://192.168.1.50:8080/"), "http://192.168.1.50:8080");
  assert.equal(LinkApi.normalizeApiBase("https://acortador.ejemplo.com///"), "https://acortador.ejemplo.com");
  assert.equal(LinkApi.normalizeApiBase("http://host:8080/base/"), "http://host:8080/base");
});

test("normalizeApiBase agrega http:// cuando falta el esquema", () => {
  assert.equal(LinkApi.normalizeApiBase("localhost:8080"), "http://localhost:8080");
  assert.equal(LinkApi.normalizeApiBase("192.168.1.50:8080"), "http://192.168.1.50:8080");
  assert.equal(LinkApi.normalizeApiBase("  10.0.0.2  "), "http://10.0.0.2");
});

test("normalizeApiBase rechaza entradas vacias, sin sentido o de otro esquema", () => {
  for (const input of ["", "   ", "ftp://archivo.com", "javascript:alert(1)", "http://", "mailto:x@y.com"]) {
    assert.throws(
      () => LinkApi.normalizeApiBase(input),
      (e) => e instanceof LinkApi.ApiError && e.code === "CONFIG_INVALID",
      `deberia rechazar ${JSON.stringify(input)}`
    );
  }
});

test("isShortenableTabUrl solo acepta pestañas http/https", () => {
  assert.equal(LinkApi.isShortenableTabUrl("https://docs.ejemplo.com/a?b=1"), true);
  assert.equal(LinkApi.isShortenableTabUrl("http://localhost:8080"), true);
  assert.equal(LinkApi.isShortenableTabUrl("HTTP://MAYUS.com"), true);
  for (const url of ["chrome://extensions", "about:addons", "file:///tmp/a.html", "ftp://x", "", undefined, null]) {
    assert.equal(LinkApi.isShortenableTabUrl(url), false, `deberia rechazar ${url}`);
  }
});

test("buildCreateLinkRequest arma el POST del contrato sobre /api/links", () => {
  const destination = "https://docs.ejemplo.com/carpeta/archivo?a=1&b=2#seccion";
  const { url, init } = LinkApi.buildCreateLinkRequest("http://192.168.1.50:8080", destination);

  assert.equal(url, "http://192.168.1.50:8080/api/links");
  assert.equal(init.method, "POST");
  assert.equal(init.headers["Content-Type"], "application/json");
  assert.equal(init.headers.Accept, "application/json");
  assert.equal(init.body, JSON.stringify({ destination }));
  assert.equal(JSON.parse(init.body).destination, destination, "el destino viaja verbatim");
});

test("buildCreateLinkRequest normaliza la base y rechaza destinos vacios", () => {
  const { url } = LinkApi.buildCreateLinkRequest("192.168.1.50:8080/", "https://x.com");
  assert.equal(url, "http://192.168.1.50:8080/api/links");

  for (const destination of ["", "   ", undefined, null]) {
    assert.throws(
      () => LinkApi.buildCreateLinkRequest("http://localhost:8080", destination),
      (e) => e instanceof LinkApi.ApiError && e.code === "INVALID_DESTINATION"
    );
  }
});

test("parseCreateLinkResponse acepta el 201 del contrato con todos los campos", () => {
  const link = LinkApi.parseCreateLinkResponse(201, VALID_LINK);
  assert.equal(link.shortUrl, VALID_LINK.shortUrl);
  assert.equal(link.alias, VALID_LINK.alias);
  assert.equal(link.expiresAt, VALID_LINK.expiresAt);
  assert.equal(link.reuseNotice, VALID_LINK.reuseNotice);
});

test("parseCreateLinkResponse no presenta como exito una respuesta incompleta", () => {
  const incomplete = [
    [201, {}],
    [201, null],
    [201, "no es un objeto"],
    [201, { shortUrl: "http://x/1" }],
    [201, { ...VALID_LINK, expiresAt: undefined }],
    [200, VALID_LINK]
  ];
  for (const [status, payload] of incomplete) {
    assert.throws(
      () => LinkApi.parseCreateLinkResponse(status, payload),
      (e) => e instanceof LinkApi.ApiError && e.code === "INVALID_RESPONSE",
      `status=${status} payload=${JSON.stringify(payload)}`
    );
  }
});

test("parseCreateLinkResponse propaga codigo y mensaje de un 400 del contrato", () => {
  try {
    LinkApi.parseCreateLinkResponse(400, { error: "OWN_ORIGIN", message: "El destino pertenece al propio servicio." });
    assert.fail("debio lanzar");
  } catch (e) {
    assert.ok(e instanceof LinkApi.ApiError);
    assert.equal(e.code, "OWN_ORIGIN");
    assert.equal(e.message, "El destino pertenece al propio servicio.");
    assert.equal(e.httpStatus, 400);
  }
});

test("parseCreateLinkResponse reporta estados inesperados con mensaje claro", () => {
  for (const status of [400, 404, 500]) {
    try {
      LinkApi.parseCreateLinkResponse(status, null);
      assert.fail(`debio lanzar para ${status}`);
    } catch (e) {
      assert.equal(e.code, `HTTP_${status}`);
      assert.equal(e.httpStatus, status);
      assert.match(e.message, /estado|status/i);
    }
  }
});

test("createLink resuelve con el enlace cuando la API responde 201", async () => {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    return fakeResponse(201, VALID_LINK);
  };
  const link = await LinkApi.createLink("http://192.168.1.50:8080", "https://x.com/a", fetchImpl);

  assert.equal(link.shortUrl, VALID_LINK.shortUrl);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, "http://192.168.1.50:8080/api/links");
  assert.equal(calls[0].init.method, "POST");
  assert.ok(calls[0].init.signal, "la solicitud lleva timeout");
});

test("createLink propaga los errores del contrato sin ocultarlos", async () => {
  const fetchImpl = async () => fakeResponse(400, { error: "MALFORMED_DESTINATION", message: "La URL de destino no tiene un formato válido." });
  await assert.rejects(
    LinkApi.createLink("http://localhost:8080", "esto no es url", fetchImpl),
    (e) => e instanceof LinkApi.ApiError && e.code === "MALFORMED_DESTINATION" && e.message.includes("formato")
  );
});

test("createLink reporta error de red nombrando la API configurada", async () => {
  const fetchImpl = async () => {
    throw new TypeError("fetch failed");
  };
  await assert.rejects(
    LinkApi.createLink("http://192.168.1.50:8080", "https://x.com", fetchImpl),
    (e) => e instanceof LinkApi.ApiError && e.code === "NETWORK_ERROR" && e.message.includes("192.168.1.50:8080")
  );
});

test("createLink reporta timeout como tal", async () => {
  const fetchImpl = async () => {
    const err = new Error("tiempo agotado");
    err.name = "TimeoutError";
    throw err;
  };
  await assert.rejects(
    LinkApi.createLink("http://localhost:8080", "https://x.com", fetchImpl),
    (e) => e instanceof LinkApi.ApiError && e.code === "REQUEST_TIMEOUT"
  );
});

test("createLink trata JSON roto en un 201 como respuesta invalida", async () => {
  const fetchImpl = async () => fakeResponse(201, null, { failJson: true });
  await assert.rejects(
    LinkApi.createLink("http://localhost:8080", "https://x.com", fetchImpl),
    (e) => e instanceof LinkApi.ApiError && e.code === "INVALID_RESPONSE"
  );
});

test("formatExpiresAt devuelve hora local y minutos restantes", () => {
  const now = new Date("2030-01-01T00:00:30Z");
  const { local, minutesLeft } = LinkApi.formatExpiresAt("2030-01-01T01:00:00Z", now);
  assert.equal(minutesLeft, 59);
  assert.equal(typeof local, "string");
  assert.ok(local.length > 0);
});

test("formatExpiresAt tolera fechas invalidas", () => {
  const { local, minutesLeft } = LinkApi.formatExpiresAt("no-es-fecha", new Date());
  assert.equal(local, "no-es-fecha");
  assert.equal(minutesLeft, null);
});
