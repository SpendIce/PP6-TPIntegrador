/*
 * Popup del complemento: toma la URL de la pestaña activa, pide la
 * creacion a la API configurada y muestra el resultado con su QR.
 * Con sesion de la web (cookie del mismo navegador) muestra el namespace
 * y el alias personalizado opcional; sin sesion acorta como en la etapa 1.
 * Capa de integracion con las APIs del navegador; la logica testeable
 * vive en LinkApi (api.js) y en QrPng (qr-code.js, compartido con la
 * web desde src/main/resources/static/).
 */
(function () {
  "use strict";

  // Firefox expone `browser` (promesas); Chrome MV3 expone `chrome`
  // (sus APIs asincronicas tambien devuelven promesas).
  const ext = typeof browser !== "undefined" ? browser : chrome;
  const STORAGE_KEY = "apiBase";

  const el = {};
  let lastQr = null;
  // Usuario de la sesion web (null sin sesion): decide si hay alias.
  let currentUser = null;
  // Cambia con la sesion o la API: las respuestas de una epoca anterior
  // (creacion, consulta de sesion) se descartan sin renderizarse.
  let epoch = 0;

  function bindElements() {
    for (const id of [
      "status", "result", "destination", "short-url", "expires", "reuse-notice",
      "qr", "download-png", "again", "error", "error-code", "error-message",
      "retry", "api-base", "save-config", "config-status",
      "account", "account-email", "account-namespace", "namespace-notice",
      "alias", "create-link", "logout", "login", "login-button"
    ]) {
      el[id] = document.getElementById(id);
    }
  }

  function setStatus(text) {
    el.status.textContent = text;
    el.status.hidden = false;
    el.result.hidden = true;
    el.error.hidden = true;
  }

  function showError(err) {
    el.status.hidden = true;
    el.result.hidden = true;
    el.error.hidden = false;
    el["error-code"].textContent = err && err.code ? err.code : "ERROR";
    el["error-message"].textContent = err && err.message ? err.message : String(err);
  }

  function expiresText(iso) {
    const { local, minutesLeft } = LinkApi.formatExpiresAt(iso);
    if (minutesLeft === null) {
      return `Vence: ${local}`;
    }
    if (minutesLeft < 0) {
      return `Venció: ${local}`;
    }
    if (minutesLeft === 0) {
      return `Vence: ${local} (en menos de un minuto)`;
    }
    return `Vence: ${local} (en ${minutesLeft} minutos)`;
  }

  function renderLink(destination, link) {
    el.status.hidden = true;
    el.error.hidden = true;
    el.result.hidden = false;

    el.destination.textContent = destination;
    el.destination.title = destination;

    el["short-url"].textContent = link.shortUrl;
    el["short-url"].href = link.shortUrl;
    el.expires.textContent = expiresText(link.expiresAt);
    el["reuse-notice"].textContent = link.reuseNotice;

    const pngBytes = QrPng.encode(qrcode, link.shortUrl);
    const qrUrl = URL.createObjectURL(new Blob([pngBytes], { type: "image/png" }));
    el.qr.src = qrUrl;
    if (lastQr) {
      URL.revokeObjectURL(lastQr.url);
    }
    lastQr = { url: qrUrl, filename: QrPng.suggestedFileName(link.alias) };
  }

  async function configuredApiBase() {
    // storage.local.get resuelve siempre un objeto (posiblemente vacio).
    const stored = await ext.storage.local.get(STORAGE_KEY);
    return LinkApi.resolveApiBase(stored[STORAGE_KEY]);
  }

  async function activeTabUrl() {
    const tabs = await ext.tabs.query({ active: true, currentWindow: true });
    const tab = tabs && tabs[0];
    return tab ? tab.url : undefined;
  }

  function renderSession(user) {
    currentUser = user;
    el.account.hidden = !user;
    el.login.hidden = Boolean(user);
    if (!user) {
      return;
    }
    const hasNamespace = user.namespace !== "";
    el["account-email"].textContent = user.email;
    el["account-namespace"].textContent = hasNamespace ? `Namespace: ${user.namespace}` : "Sin namespace elegido";
    el["namespace-notice"].hidden = hasNamespace;
    // Sin namespace el contrato rechaza el alias (NAMESPACE_NOT_CHOSEN).
    el.alias.disabled = !hasNamespace;
    if (!hasNamespace) {
      el.alias.value = "";
    }
  }

  async function refreshSession() {
    const mine = epoch;
    let user = null;
    try {
      user = await LinkApi.getSession(await configuredApiBase());
    } catch {
      // Sin respuesta de sesion se conserva el flujo de la etapa 1;
      // si la API no responde, shortenActiveTab muestra el error.
    }
    if (mine === epoch) {
      renderSession(user);
    }
  }

  async function shortenActiveTab() {
    const mine = epoch;
    try {
      setStatus("Leyendo la pestaña activa…");
      const url = await activeTabUrl();
      if (!LinkApi.isShortenableTabUrl(url)) {
        throw new LinkApi.ApiError(
          "TAB_NOT_SHORTENABLE",
          "La pestaña activa no es una página http/https; solo esas direcciones pueden acortarse."
        );
      }

      const apiBase = await configuredApiBase();
      setStatus(`Acortando en ${apiBase}…`);
      const alias = currentUser ? el.alias.value.trim() : "";
      const link = await LinkApi.createLink(apiBase, url, undefined, alias);
      if (mine === epoch) {
        renderLink(url, link);
      }
    } catch (err) {
      if (mine === epoch) {
        showError(err);
      }
    }
  }

  async function openLogin() {
    try {
      await ext.tabs.create({ url: LinkApi.buildLoginUrl(await configuredApiBase()) });
    } catch (err) {
      showError(err);
    }
  }

  async function endSession() {
    try {
      epoch += 1;
      setStatus("Cerrando sesión…");
      await LinkApi.logout(await configuredApiBase());
      el.alias.value = "";
      renderSession(null);
      await shortenActiveTab();
    } catch (err) {
      showError(err);
    }
  }

  function downloadQr() {
    if (!lastQr) {
      return;
    }
    const pending = ext.downloads.download({
      url: lastQr.url,
      filename: lastQr.filename,
      saveAs: false
    });
    if (pending && typeof pending.catch === "function") {
      pending.catch(() => showError({ code: "DOWNLOAD_FAILED", message: "No se pudo descargar el PNG." }));
    }
  }

  function setConfigStatus(text) {
    el["config-status"].textContent = text;
  }

  async function saveConfig() {
    try {
      const input = el["api-base"].value.trim();
      const normalized = input ? LinkApi.normalizeApiBase(input) : LinkApi.DEFAULT_API_BASE;
      await ext.storage.local.set({ [STORAGE_KEY]: normalized });
      el["api-base"].value = normalized;
      setConfigStatus(`Guardada: ${normalized}`);
      // La cuenta mostrada pertenecia a la API anterior: se descarta y se
      // consulta la sesion de la nueva antes de permitir crear o cerrar.
      epoch += 1;
      el.alias.value = "";
      renderSession(null);
      await refreshSession();
    } catch (err) {
      setConfigStatus(err && err.message ? err.message : String(err));
    }
  }

  async function loadConfig() {
    const base = await configuredApiBase();
    el["api-base"].value = base;
    el["api-base"].placeholder = LinkApi.DEFAULT_API_BASE;
  }

  function init() {
    bindElements();
    el.retry.addEventListener("click", shortenActiveTab);
    el.again.addEventListener("click", shortenActiveTab);
    el["create-link"].addEventListener("click", shortenActiveTab);
    el.logout.addEventListener("click", endSession);
    el["login-button"].addEventListener("click", openLogin);
    el["download-png"].addEventListener("click", downloadQr);
    el["save-config"].addEventListener("click", saveConfig);
    loadConfig();
    start();
  }

  // Con sesion espera el alias opcional; sin sesion acorta de inmediato.
  async function start() {
    await refreshSession();
    if (currentUser) {
      el.status.hidden = true;
    } else {
      await shortenActiveTab();
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
