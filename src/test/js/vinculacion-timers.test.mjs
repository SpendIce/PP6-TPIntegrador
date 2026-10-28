/*
 * La renovación del QR se programa contra el reloj del servidor y con un
 * piso que deja consultar el estado al menos una vez antes de reemplazar el
 * código, pero sin pasar nunca del vencimiento: con el TTL mínimo admitido
 * (5 s, ver CuentaProperties) o con latencia entre la respuesta y el
 * temporizador, el techo es lo que resta del código menos un margen.
 */
import test from "node:test";
import assert from "node:assert/strict";
import timers from "../../main/resources/static/vinculacion-timers.js";

const { proximaRenovacionMs } = timers;

const CONSULTA = 2000;
const RENOVACION = 1000;
const MARGEN = 500;
const PISO = CONSULTA + MARGEN;

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

test("con un TTL menor que la renovación, el piso permite un ciclo de estado", () => {
    const servidor = Date.parse("2026-10-27T02:00:00Z");
    const expira = new Date(servidor + 5_000).toISOString();

    // Caso con renovación mayor que el TTL: el resultado no puede bajar del
    // piso, así que la primera consulta (2 s) corre antes de renovar.
    const espera = proximaRenovacionMs(expira, new Date(servidor).toUTCString(), CONSULTA, 6_000);

    assert.equal(espera, PISO);
});

test("la renovación nunca se programa después del vencimiento", () => {
    const servidor = Date.parse("2026-10-27T02:00:00Z");

    // El piso (2,5 s) no puede mandar sobre lo que resta: con 3 s de
    // restante la renovación sale a lo sumo un margen antes de vencer.
    for (const restante of [3_000, 2_600, PISO + 1]) {
        const expira = new Date(servidor + restante).toISOString();
        const espera = proximaRenovacionMs(
                expira, new Date(servidor).toUTCString(), CONSULTA, 6_000);

        assert.ok(espera < restante,
                `con ${restante} ms de restante la espera ${espera} pasa el vencimiento`);
        assert.ok(espera <= restante - MARGEN,
                `con ${restante} ms de restante la espera ${espera} no deja margen`);
    }
});

test("un código que llega vencido se reemplaza tras un respiro, no tras el piso", () => {
    const servidor = Date.parse("2026-10-27T02:00:00Z");

    for (const restante of [MARGEN, 0, -60_000]) {
        const expira = new Date(servidor + restante).toISOString();
        assert.equal(
                proximaRenovacionMs(expira, new Date(servidor).toUTCString(), CONSULTA, RENOVACION),
                MARGEN);
    }
});

test("sin encabezado Date el reloj del dispositivo es la referencia", () => {
    const expira = new Date(Date.now() + 60_000).toISOString();

    const espera = proximaRenovacionMs(expira, undefined, CONSULTA, RENOVACION);

    assert.ok(espera <= 59_000 && espera > 58_000,
            `sin Date esperaba ~59 s de espera, dio ${espera}`);
});

test("un vencimiento ilegible cae al piso", () => {
    assert.equal(proximaRenovacionMs("no-es-fecha", undefined, CONSULTA, RENOVACION), PISO);
});
