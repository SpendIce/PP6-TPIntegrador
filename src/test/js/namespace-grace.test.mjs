// Pruebas de la página «Mi namespace» (issue #24). El criterio de
// aceptación exige que la página liste los namespaces anteriores en
// gracia con su tiempo restante y ofrezca «Volver a este»; el cálculo y
// el render viven en namespace-grace.js, que app.js usa tal cual.
//
// Ejecutar: cd src/test/js && node --test

import { test } from "node:test";
import assert from "node:assert/strict";
import NamespaceGracia from "../../main/resources/static/namespace-grace.js";

function elemento(tag) {
    return {
        tag: tag,
        children: [],
        dataset: {},
        listeners: {},
        className: "",
        type: "",
        _texto: "",
        set textContent(valor) {
            this._texto = String(valor);
            this.children = [];
        },
        get textContent() {
            return this._texto + this.children
                .map((hijo) => typeof hijo === "string" ? hijo : hijo.textContent)
                .join("");
        },
        append(...nodos) {
            this.children.push(...nodos);
        },
        replaceChildren(...nodos) {
            this.children = [...nodos];
        },
        addEventListener(evento, accion) {
            this.listeners[evento] = accion;
        },
        click() {
            this.listeners.click();
        }
    };
}

function documentoFalso() {
    return { createElement: (tag) => elemento(tag) };
}

test("el tiempo restante se muestra en horas y minutos redondeando hacia arriba", () => {
    const ahora = Date.parse("2026-10-05T12:00:00Z");

    assert.equal(
        NamespaceGracia.tiempoRestante("2026-10-05T13:00:00Z", ahora), "1 h 0 min");
    assert.equal(
        NamespaceGracia.tiempoRestante("2026-10-05T12:30:00Z", ahora), "30 min");
    assert.equal(
        NamespaceGracia.tiempoRestante("2026-10-05T12:00:30Z", ahora), "1 min");
});

test("un fin de gracia ya cumplido no produce tiempos negativos", () => {
    const ahora = Date.parse("2026-10-05T14:00:00Z");

    assert.equal(
        NamespaceGracia.tiempoRestante("2026-10-05T13:00:00Z", ahora), "0 min");
});

test("la lista muestra cada anterior con su fin y su tiempo restante", () => {
    const contenedor = elemento("ul");
    const ahora = Date.parse("2026-10-05T12:00:00Z");

    NamespaceGracia.renderAnteriores(documentoFalso(), contenedor, [
        { namespace: "juan", graceEndsAt: "2026-10-05T13:00:00Z" },
        { namespace: "juani", graceEndsAt: "2026-10-05T12:30:00Z" }
    ], ahora, () => {});

    assert.equal(contenedor.children.length, 2);

    const primero = contenedor.children[0];
    assert.equal(primero.textContent, "juan · 1 h 0 min Volver a este");
    assert.equal(primero.children[2].className, "grace-remaining");
    assert.equal(primero.children[2].dataset.endsAt, "2026-10-05T13:00:00Z");

    const segundo = contenedor.children[1];
    assert.equal(segundo.textContent, "juani · 30 min Volver a este");
    assert.equal(segundo.children[2].dataset.endsAt, "2026-10-05T12:30:00Z");
});

test("«Volver a este» devuelve el namespace anterior elegido", () => {
    const contenedor = elemento("ul");
    const elegidos = [];

    NamespaceGracia.renderAnteriores(documentoFalso(), contenedor, [
        { namespace: "juan", graceEndsAt: "2026-10-05T13:00:00Z" }
    ], Date.parse("2026-10-05T12:00:00Z"), (namespace) => elegidos.push(namespace));

    const boton = contenedor.children[0].children[4];
    assert.equal(boton.type, "button");
    assert.equal(boton.textContent, "Volver a este");
    boton.click();

    assert.deepEqual(elegidos, ["juan"]);
});
