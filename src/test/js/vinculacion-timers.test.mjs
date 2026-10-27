/*
 * La renovación del QR se programa contra el reloj del servidor y con un piso
 * que deja consultar el estado al menos una vez antes de reemplazar el código.
 */
import test from "node:test";
import assert from "node:assert/strict";
import timers from "../../main/resources/static/vinculacion-timers.js";

const { proximaRenovacionMs } = timers;

const CONSULTA = 2000;
const RENOVACION = 1000;
const PISO = CONSULTA + 500;

test("con relojes sincronizados renueva con la antelación pedida", () => {
    const ahora = Date.parse("2026-10-27T02:00:00Z");
    const expira = new Date(ahora + 60_000).toISOString();
    const header = new Date(ahora).toUTCString();

    assert.equal(proximaRenovacionMs(expira, header, CONSULTA, RENOVACION), 59_000);
});

test("con el reloj del dispositivo adelantado no entra en loop", () => {
    // El `Date` del servidor marca ahora; `Date.now()` (reloj del dispositivo)
    // iría muy adelantado, pero no se usa.
    const servidor = Date.parse("2026-10-27T02:00:00Z");
    const expira = new Date(servidor + 5_000).toISOString();

    const espera = proximaRenovacionMs(expira, new Date(servidor).toUTCString(), CONSULTA, RENOVACION);

    assert.equal(espera, 5_000 - RENOVACION);
    assert.ok(espera >= CONSULTA, "deja consultar el estado antes de renovar");
});

test("con un TTL menor que la consulta, el piso permite un ciclo de estado", () => {
    const servidor = Date.parse("2026-10-27T02:00:00Z");
    const expira = new Date(servidor + 5_000).toISOString();

    // Caso con renovación mayor que el TTL: el resultado no puede bajar del
    // piso, así que la primera consulta (2 s) corre antes de renovar.
    const espera = proximaRenovacionMs(expira, new Date(servidor).toUTCString(), CONSULTA, 6_000);

    assert.equal(espera, PISO);
});

test("sin encabezado Date usa el piso y no un valor negativo", () => {
    const expira = new Date(Date.now() - 60_000).toISOString();

    assert.equal(proximaRenovacionMs(expira, undefined, CONSULTA, RENOVACION), PISO);
});

test("un vencimiento ilegible cae al piso", () => {
    assert.equal(proximaRenovacionMs("no-es-fecha", undefined, CONSULTA, RENOVACION), PISO);
});
