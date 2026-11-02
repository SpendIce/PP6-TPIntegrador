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
  const CSRF_PATH = "/api/csrf";
  const SESSION_PATH = "/api/sesion";
  const LOGOUT_PATH = "/api/logout";
  const LOGIN_PATH = "/oauth2/authorization/google";
  const CSRF_HEADER = "X-XSRF-TOKEN";
  const REQUEST_TIMEOUT_MS = 10000;

  /*
   * Error con codigo estable. Los codigos propios del cliente son
   * CONFIG_INVALID, INVALID_DESTINATION, TAB_NOT_SHORTENABLE,
   * NETWORK_ERROR, REQUEST_TIMEOUT, INVALID_RESPONSE, CSRF_UNAVAILABLE
   * y HTTP_<estado>; los rechazos de la API conservan el `error` del
   * contrato (EMPTY_DESTINATION, MALFORMED_DESTINATION, OWN_ORIGIN,
   * CSRF_TOKEN_INVALID, ALIAS_INVALID, ALIAS_TAKEN, NAMESPACE_NOT_CHOSEN,
   * NOT_AUTHENTICATED, etc.).
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
    } catch {
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

  /* El destino vacio se rechaza antes de cualquier llamada a la API. */
  function requireDestination(destination) {
    if (typeof destination !== "string" || destination.trim() === "") {
      throw new ApiError("INVALID_DESTINATION", "No hay una URL de destino para acortar.");
    }
  }

  /* Solo las pestañas http/https pueden enviarse a la API. */
  function isShortenableTabUrl(url) {
    return typeof url === "string" && /^https?:\/\//i.test(url);
  }

  /*
   * buildCsrfRequest(apiBase) -> { url, init }
   * Arma el GET /api/csrf del contrato: devuelve {"token": "<valor>"} y
   * fija la cookie XSRF-TOKEN. `credentials: "include"` pide al
   * navegador guardar esa cookie aunque el popup sea otro origen, y
   * reenviarla en el POST.
   */
  function buildCsrfRequest(apiBase) {
    const base = normalizeApiBase(apiBase);
    return {
      url: `${base}${CSRF_PATH}`,
      init: {
        method: "GET",
        headers: { Accept: "application/json" },
        credentials: "include"
      }
    };
  }

  /*
   * buildCreateLinkRequest(apiBase, destination, csrfToken) -> { url, init }
   * Arma el POST del contrato: JSON con el destino verbatim y el token
   * CSRF repetido en el encabezado X-XSRF-TOKEN (doble envio); la cookie
   * XSRF-TOKEN viaja por `credentials: "include"`. Con un alias
   * personalizado no vacio agrega `aliasPersonalizado` (enlace personal,
   * requiere sesion); vacio o ausente conserva el cuerpo de la etapa 1.
   */
  function buildCreateLinkRequest(apiBase, destination, csrfToken, aliasPersonalizado) {
    const base = normalizeApiBase(apiBase);
    requireDestination(destination);
    if (typeof csrfToken !== "string" || csrfToken === "") {
      throw new ApiError("CSRF_UNAVAILABLE", `La API en ${base} no entregó un token CSRF utilizable.`);
    }
    const alias = typeof aliasPersonalizado === "string" ? aliasPersonalizado.trim() : "";
    const body = alias ? { destination, aliasPersonalizado: alias } : { destination };
    return {
      url: `${base}${CREATE_LINK_PATH}`,
      init: {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "application/json",
          [CSRF_HEADER]: csrfToken
        },
        credentials: "include",
        body: JSON.stringify(body)
      }
    };
  }

  /*
   * fetchConTimeout(doFetch, url, init) -> Promise<response>
   * Solicitud con el timeout del cliente; traduce los cortes a los
   * codigos propios NETWORK_ERROR y REQUEST_TIMEOUT.
   */
  async function fetchConTimeout(doFetch, url, init, base) {
    const signal = typeof AbortSignal === "function" && typeof AbortSignal.timeout === "function"
      ? AbortSignal.timeout(REQUEST_TIMEOUT_MS)
      : undefined;
    try {
      return await doFetch(url, { ...init, signal });
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
  }

  /*
   * obtainCsrfToken(base, doFetch) -> Promise<token>
   * Pide el token CSRF del contrato; cualquier respuesta que no sea el
   * {"token": "..."} del contrato es CSRF_UNAVAILABLE.
   */
  async function obtainCsrfToken(base, doFetch) {
    const { url, init } = buildCsrfRequest(base);
    const response = await fetchConTimeout(doFetch, url, init, base);
    const payload = await response.json().catch(() => undefined);
    if (response.status !== 200
      || !payload || typeof payload !== "object"
      || typeof payload.token !== "string" || payload.token === "") {
      throw new ApiError(
        "CSRF_UNAVAILABLE",
        `La API en ${base} no entregó el token CSRF del contrato.`,
        response.status
      );
    }
    return payload.token;
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
   * Recorrido completo del contrato: GET /api/csrf para el token (con la
   * cookie XSRF-TOKEN que el navegador reenvia por credentials:"include")
   * y luego el POST con el token en X-XSRF-TOKEN. fetchImpl es
   * inyectable para las pruebas; por defecto usa el fetch global del
   * navegador con un timeout de REQUEST_TIMEOUT_MS en cada solicitud.
   * `aliasPersonalizado` opcional crea un enlace personal.
   */
  async function createLink(apiBase, destination, fetchImpl, aliasPersonalizado) {
    requireDestination(destination);
    const base = normalizeApiBase(apiBase);
    requireDestination(destination);
    const doFetch = resolveFetch(fetchImpl);

    const csrfToken = await obtainCsrfToken(base, doFetch);
    const { url, init } = buildCreateLinkRequest(base, destination, csrfToken, aliasPersonalizado);
    const response = await fetchConTimeout(doFetch, url, init, base);

    const payload = await response.json().catch(() => undefined);
    return parseCreateLinkResponse(response.status, payload);
  }

  function resolveFetch(fetchImpl) {
    const doFetch = fetchImpl || (typeof fetch === "function" ? fetch.bind(globalThis) : null);
    if (!doFetch) {
      throw new ApiError("NETWORK_ERROR", "Este navegador no provee fetch para contactar la API.");
    }
    return doFetch;
  }

  /*
   * parseSessionResponse(status, payload) -> null | { email, namespace, tipo, sugerencia }
   * El contrato no distingue «sin sesion» de «sesion sin usuario»: ambos
   * son usuario null. Cualquier otra forma es INVALID_RESPONSE.
   */
  function parseSessionResponse(status, payload) {
    if (status !== 200 || !payload || typeof payload !== "object" || !("usuario" in payload)) {
      throw new ApiError("INVALID_RESPONSE", `La API respondió con un formato de sesión inesperado (estado ${status}).`, status);
    }
    const user = payload.usuario;
    if (user === null) {
      return null;
    }
    if (!user || typeof user !== "object" || typeof user.email !== "string" || user.email === ""
      || typeof user.namespace !== "string") {
      throw new ApiError("INVALID_RESPONSE", "La API respondió con un usuario de sesión incompleto.", status);
    }
    return {
      email: user.email,
      namespace: user.namespace,
      tipo: typeof user.tipo === "string" ? user.tipo : "",
      sugerencia: typeof user.sugerencia === "string" ? user.sugerencia : ""
    };
  }

  /*
   * getSession(apiBase, fetchImpl) -> Promise<null | usuario>
   * GET /api/sesion con la cookie de sesion de la web del mismo
   * navegador (credentials: "include"). null significa sin sesion.
   */
  async function getSession(apiBase, fetchImpl) {
    const base = normalizeApiBase(apiBase);
    const doFetch = resolveFetch(fetchImpl);
    const response = await fetchConTimeout(doFetch, `${base}${SESSION_PATH}`, {
      method: "GET",
      headers: { Accept: "application/json" },
      credentials: "include"
    }, base);
    const payload = await response.json().catch(() => undefined);
    return parseSessionResponse(response.status, payload);
  }

  /* URL web que inicia el login con Google; se abre en una pestaña. */
  function buildLoginUrl(apiBase) {
    return `${normalizeApiBase(apiBase)}${LOGIN_PATH}`;
  }

  /*
   * logout(apiBase, fetchImpl) -> Promise<void>
   * POST /api/logout con CSRF de doble envio; el contrato responde 204.
   */
  async function logout(apiBase, fetchImpl) {
    const base = normalizeApiBase(apiBase);
    const doFetch = resolveFetch(fetchImpl);
    const csrfToken = await obtainCsrfToken(base, doFetch);
    const response = await fetchConTimeout(doFetch, `${base}${LOGOUT_PATH}`, {
      method: "POST",
      headers: { Accept: "application/json", [CSRF_HEADER]: csrfToken },
      credentials: "include"
    }, base);
    if (response.status === 204) {
      return;
    }
    const payload = await response.json().catch(() => undefined);
    if (response.status >= 200 && response.status < 300) {
      throw new ApiError("INVALID_RESPONSE", `La API respondió con estado ${response.status}, distinto del 204 del contrato.`, response.status);
    }
    parseCreateLinkResponse(response.status, payload);
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
    CSRF_PATH,
    CSRF_HEADER,
    SESSION_PATH,
    LOGOUT_PATH,
    LOGIN_PATH,
    REQUEST_TIMEOUT_MS,
    ApiError,
    resolveApiBase,
    normalizeApiBase,
    isShortenableTabUrl,
    buildCsrfRequest,
    buildCreateLinkRequest,
    obtainCsrfToken,
    parseCreateLinkResponse,
    createLink,
    parseSessionResponse,
    getSession,
    buildLoginUrl,
    logout,
    formatExpiresAt
  };
});
