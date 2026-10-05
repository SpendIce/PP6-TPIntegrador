/*
 * Popup del complemento: toma la URL de la pestaña activa, pide la
 * creacion a la API configurada y muestra el resultado con su QR.
 * Capa de integracion con las APIs del navegador; la logica testeable
 * vive en LinkApi (api.js) y QrImage (qr.js).
 */
(function () {
  "use strict";

  // Firefox expone `browser` (promesas); Chrome MV3 expone `chrome`
  // (sus APIs asincronicas tambien devuelven promesas).
  const ext = typeof browser !== "undefined" ? browser : chrome;
  const STORAGE_KEY = "apiBase";

  const el = {};
  let lastQr = null;

  function bindElements() {
    for (const id of [
      "status", "result", "destination", "short-url", "expires", "reuse-notice",
      "qr", "download-png", "again", "error", "error-code", "error-message",
      "retry", "api-base", "save-config", "config-status"
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

    const dataUrl = QrImage.pngDataUrl(link.shortUrl);
    el.qr.src = dataUrl;
    lastQr = { dataUrl, filename: `qr-${link.alias}.png` };
  }

  async function configuredApiBase() {
    const stored = await ext.storage.local.get(STORAGE_KEY);
    return LinkApi.resolveApiBase(stored ? stored[STORAGE_KEY] : undefined);
  }

  async function activeTabUrl() {
    const tabs = await ext.tabs.query({ active: true, currentWindow: true });
    const tab = tabs && tabs[0];
    return tab ? tab.url : undefined;
  }

  async function shortenActiveTab() {
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
      const link = await LinkApi.createLink(apiBase, url);
      renderLink(url, link);
    } catch (err) {
      showError(err);
    }
  }

  function downloadQr() {
    if (!lastQr) {
      return;
    }
    const pending = ext.downloads.download({
      url: lastQr.dataUrl,
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
    el["download-png"].addEventListener("click", downloadQr);
    el["save-config"].addEventListener("click", saveConfig);
    loadConfig();
    shortenActiveTab();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
