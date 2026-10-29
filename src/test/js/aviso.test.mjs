/*
 * El script de la página de aviso del namespace anterior (issue #25)
 * corre sobre el aviso.js real con un DOM mínimo (web-stub.mjs) y
 * temporizadores falsos: la cuenta descuenta un segundo por tick, al
 * llegar a cero navega a la nueva dirección del enlace «Ir ahora» y el
 * botón copia la dirección al portapapeles o la ofrece en un prompt.
 *
 * Ejecutar: cd src/test/js && node --test
 */
import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { crearDocumento, drenar } from "./web-stub.mjs";

const require = createRequire(import.meta.url);
const AVISO = require.resolve("../../main/resources/static/aviso.js");

const NUEVA = "http://192.168.50.10:8080/juani/mi-doc";

/**
 * Instala document/window falsos, arma el aviso con la demora en
 * `segundos` y carga aviso.js fresco, como haría el navegador con la
 * etiqueta <script>.
 */
function instalar(t, { segundos = 5, conPortapapeles = true } = {}) {
    t.mock.timers.enable({ apis: ["setTimeout", "setInterval", "Date"], now: 0 });
    const documento = crearDocumento();
    const escritos = [];
    const prompts = [];
    const navegado = { a: null };
    documento.getElementById("ir-ahora").href = NUEVA;
    documento.getElementById("cuenta").textContent = String(segundos);
    globalThis.document = documento;
    globalThis.window = {
        navigator: conPortapapeles
            ? { clipboard: { writeText: async (texto) => escritos.push(texto) } }
            : {},
        location: { set href(valor) { navegado.a = valor; }, get href() { return navegado.a; } },
        prompt: (mensaje, valor) => prompts.push([mensaje, valor])
    };
    delete require.cache[AVISO];
    require(AVISO);
    return { documento, escritos, prompts, navegado };
}

test("la cuenta regresiva descuenta un segundo por tick y navega al llegar a cero", (t) => {
    const { documento, navegado } = instalar(t, { segundos: 3 });
    const cuenta = documento.getElementById("cuenta");

    t.mock.timers.tick(1000);
    assert.equal(cuenta.textContent, "2");
    t.mock.timers.tick(2000);
    assert.equal(cuenta.textContent, "0");
    assert.equal(navegado.a, NUEVA);

    // El reloj queda frenado: no hay ticks ni navegaciones extra.
    t.mock.timers.tick(5000);
    assert.equal(cuenta.textContent, "0");
    assert.equal(navegado.a, NUEVA);
});

test("el botón copia la nueva dirección al portapapeles", async (t) => {
    const { documento, escritos } = instalar(t);

    await documento.getElementById("copiar").emit("click");
    await drenar();

    assert.deepEqual(escritos, [NUEVA]);
    assert.equal(documento.getElementById("copiar").textContent, "Copiada");
});

test("sin API de portapapeles el botón ofrece la dirección en un prompt", async (t) => {
    const { documento, prompts } = instalar(t, { conPortapapeles: false });

    await documento.getElementById("copiar").emit("click");
    await drenar();

    assert.equal(prompts.length, 1);
    assert.equal(prompts[0][1], NUEVA);
});
