/*
 * LinkApi — cliente del contrato POST /api/links del acortador.
 *
 * Funciones puras y testeables en node: construccion de la solicitud,
 * parseo de la respuesta segun openapi.yaml (201 con shortUrl, alias,
 * expiresAt y reuseNotice; errores con {error, message}), normalizacion
 * de la direccion configurable de la API y formato del vencimiento.
 * El unico efecto esta en createLink, que recibe el fetch por inyeccion.
 *
 * En el navegador queda como global `LinkApi`; en node se exporta por
 * CommonJS para `node --test`.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) {
    module.exports = api;
  }
  root.LinkApi = api;
})(typeof globalThis === "object" ? globalThis : this, function () {
  "use strict";

  const DEFAULT_API_BASE = "http://localhost:8080";
  const CREATE_LINK_PATH = "/api/links";
  const REQUEST_TIMEOUT_MS = 10000;

  /*
   * Error con codigo estable. Los codigos propios del cliente son
   * CONFIG_INVALID, INVALID_DESTINATION, TAB_NOT_SHORTENABLE,
   * NETWORK_ERROR, REQUEST_TIMEOUT, INVALID_RESPONSE y HTTP_<estado>;
   * los rechazos de la API conservan el `error` del contrato
   * (EMPTY_DESTINATION, MALFORMED_DESTINATION, OWN_ORIGIN, etc.).
   */
  class ApiError extends Error {
    constructor(code, message, httpStatus) {
      super(message);
      this.name = "ApiError";
      this.code = code;
      if (httpStatus !== undefined) {
        this.httpStatus = httpStatus;
      }
    }
  }

  /* Direccion de API efectiva: la configurada o el valor por defecto. */
  function resolveApiBase(saved) {
    const value = typeof saved === "string" ? saved.trim() : "";
    return value || DEFAULT_API_BASE;
  }

  /*
   * Normaliza la direccion de la API ingresada por el usuario.
   * Acepta "host[:puerto]" sin esquema (se asume http://, tipico en LAN),
   * exige esquema http o https, sin credenciales, y quita barras finales.
   */
  function normalizeApiBase(input) {
    let value = typeof input === "string" ? input.trim() : "";
    if (!value) {
      throw new ApiError("CONFIG_INVALID", "La dirección de la API no puede estar vacía.");
    }
    if (!value.includes("://")) {
      value = `http://${value}`;
    }

    let url;
    try {
      url = new URL(value);
    } catch (e) {
      throw new ApiError("CONFIG_INVALID", `«${input}» no es una dirección válida. Ejemplo: http://192.168.1.50:8080`);
    }
    if (url.protocol !== "http:" && url.protocol !== "https:") {
      throw new ApiError("CONFIG_INVALID", `La API debe usar http o https (se recibió ${url.protocol.replace(":", "")}).`);
    }
    if (url.username || url.password) {
      throw new ApiError("CONFIG_INVALID", "La dirección de la API no admite credenciales.");
    }

    const path = url.pathname === "/" ? "" : url.pathname;
    return (url.origin + path).replace(/\/+$/, "");
  }

  /* Solo las pestañas http/https pueden enviarse a la API. */
  function isShortenableTabUrl(url) {
    return typeof url === "string" && /^https?:\/\//i.test(url);
  }

  /*
   * buildCreateLinkRequest(apiBase, destination) -> { url, init }
   * Arma el POST del contrato: JSON con el destino verbatim.
   */
  function buildCreateLinkRequest(apiBase, destination) {
    const base = normalizeApiBase(apiBase);
    if (typeof destination !== "string" || destination.trim() === "") {
      throw new ApiError("INVALID_DESTINATION", "No hay una URL de destino para acortar.");
    }
    return {
      url: `${base}${CREATE_LINK_PATH}`,
      init: {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "application/json"
        },
        body: JSON.stringify({ destination })
      }
    };
  }

  /*
   * parseCreateLinkResponse(status, payload) -> { shortUrl, alias, expiresAt, reuseNotice }
   * Lanza ApiError para cualquier respuesta que no sea el 201 completo del
   * contrato: un fallo nunca se presenta como exito.
   */
  function parseCreateLinkResponse(status, payload) {
    if (status === 201) {
      const valid = payload && typeof payload === "object"
        && typeof payload.shortUrl === "string" && payload.shortUrl.length > 0
        && typeof payload.alias === "string" && payload.alias.length > 0
        && typeof payload.expiresAt === "string" && payload.expiresAt.length > 0
        && typeof payload.reuseNotice === "string";
      if (!valid) {
        throw new ApiError(
          "INVALID_RESPONSE",
          "La API respondió con un formato inesperado (faltan campos del contrato).",
          status
        );
      }
      return {
        shortUrl: payload.shortUrl,
        alias: payload.alias,
        expiresAt: payload.expiresAt,
        reuseNotice: payload.reuseNotice
      };
    }

    if (status >= 200 && status < 300) {
      throw new ApiError("INVALID_RESPONSE", `La API respondió con estado ${status}, distinto del 201 del contrato.`, status);
    }

    const code = payload && typeof payload === "object" && typeof payload.error === "string" && payload.error
      ? payload.error
      : `HTTP_${status}`;
    const message = payload && typeof payload === "object" && typeof payload.message === "string" && payload.message
      ? payload.message
      : `La API respondió con estado ${status} sin detalle.`;
    throw new ApiError(code, message, status);
  }

  /*
   * createLink(apiBase, destination, fetchImpl) -> Promise<link>
   * fetchImpl es inyectable para las pruebas; por defecto usa el fetch
   * global del navegador con un timeout de REQUEST_TIMEOUT_MS.
   */
  async function createLink(apiBase, destination, fetchImpl) {
    const base = normalizeApiBase(apiBase);
    const { url, init } = buildCreateLinkRequest(base, destination);
    const doFetch = fetchImpl || (typeof fetch === "function" ? fetch.bind(globalThis) : null);
    if (!doFetch) {
      throw new ApiError("NETWORK_ERROR", "Este navegador no provee fetch para contactar la API.");
    }

    const signal = typeof AbortSignal === "function" && typeof AbortSignal.timeout === "function"
      ? AbortSignal.timeout(REQUEST_TIMEOUT_MS)
      : undefined;

    let response;
    try {
      response = await doFetch(url, { ...init, signal });
    } catch (e) {
      if (e && (e.name === "TimeoutError" || e.name === "AbortError")) {
        throw new ApiError(
          "REQUEST_TIMEOUT",
          `La API en ${base} no respondió en ${REQUEST_TIMEOUT_MS / 1000} segundos.`
        );
      }
      throw new ApiError(
        "NETWORK_ERROR",
        `No se pudo conectar con la API en ${base}. Verificá que el servicio esté en ejecución y que la dirección configurada sea correcta.`
      );
    }

    const payload = await response.json().catch(() => undefined);
    return parseCreateLinkResponse(response.status, payload);
  }

  /*
   * formatExpiresAt(iso, now) -> { local, minutesLeft }
   * local es la hora formateada con la locale del navegador; minutesLeft
   * es entero (puede ser <= 0 si ya venció). Fechas invalidas devuelven
   * el texto crudo y minutesLeft null.
   */
  function formatExpiresAt(iso, now) {
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) {
      return { local: String(iso), minutesLeft: null };
    }
    const reference = now instanceof Date ? now : new Date();
    const minutesLeft = Math.floor((date.getTime() - reference.getTime()) / 60000);
    return { local: date.toLocaleString(), minutesLeft };
  }

  return {
    DEFAULT_API_BASE,
    CREATE_LINK_PATH,
    REQUEST_TIMEOUT_MS,
    ApiError,
    resolveApiBase,
    normalizeApiBase,
    isShortenableTabUrl,
    buildCreateLinkRequest,
    parseCreateLinkResponse,
    createLink,
    formatExpiresAt
  };
});
