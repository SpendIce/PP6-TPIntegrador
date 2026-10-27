/*
 * Temporizadores de la vinculación por QR (issue #22).
 *
 * La renovación del código tiene que calcularse contra el reloj del servidor
 * (el encabezado `Date` de la respuesta): con el reloj del dispositivo
 * adelantado, `expiresAt - Date.now()` da negativo y se pedirían códigos
 * nuevos sin parar. El piso es una consulta completa más un margen, para que
 * el estado alcance a consultarse antes de reemplazar el código, y el techo
 * es el vencimiento menos el margen: la renovación siempre sale antes de que
 * el QR mostrado venza, incluso con el `link-code-ttl` mínimo que la
 * configuración admite.
 *
 * UMD mínimo: el navegador lo usa como global y `node --test` lo importa.
 */
(function (root) {
    "use strict";

    var MARGEN_RENOVACION_MS = 500;

    /**
     * Milisegundos hasta renovar el código.
     *
     * @param {string} expiresAtIso  Vencimiento del código (ISO-8601).
     * @param {string|undefined} dateHeader  Encabezado `Date` de la respuesta.
     * @param {number} consultaMs  Intervalo de consulta del estado.
     * @param {number} renovacionMs  Antelación con que se renueva.
     */
    function proximaRenovacionMs(expiresAtIso, dateHeader, consultaMs, renovacionMs) {
        var piso = consultaMs + MARGEN_RENOVACION_MS;
        var expira = Date.parse(expiresAtIso);
        if (!isFinite(expira)) {
            return piso;
        }
        var servidor = Date.parse(dateHeader || "");
        var referencia = isFinite(servidor) ? servidor : Date.now();
        var restante = expira - referencia;
        // Un código que llega vencido (o a un margen de vencer) no se
        // exhibe el piso completo: se pide otro tras un respiro.
        if (restante <= MARGEN_RENOVACION_MS) {
            return MARGEN_RENOVACION_MS;
        }
        // El piso deja consultar el estado una vez antes de renovar, pero
        // el techo manda: renovar antes del vencimiento aunque el piso
        // supere lo que resta del código.
        var techo = restante - MARGEN_RENOVACION_MS;
        return Math.min(Math.max(restante - renovacionMs, piso), techo);
    }

    var api = { proximaRenovacionMs: proximaRenovacionMs };
    if (typeof module === "object" && module.exports) {
        module.exports = api;
    } else {
        root.VinculacionTimers = api;
    }
})(typeof globalThis !== "undefined" ? globalThis : this);
