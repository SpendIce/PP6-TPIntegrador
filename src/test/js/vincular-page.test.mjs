/*
 * La página /vincular/{codigo} (issue #22) corre sobre el vincular.js real
 * con un DOM mínimo (web-stub.mjs): consulta la confirmación, muestra el
 * namespace del usuario (o el email si no lo eligió) y solo el botón
 * aprueba — abrir la página no confirma nada. Una sesión vinculada no puede
 * aprobar y un código inválido pide uno nuevo.
 *
 * Ejecutar: cd src/test/js && node --test
 */
import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { crearDocumento, crearFetch, respuesta, drenar } from "./web-stub.mjs";

const require = createRequire(import.meta.url);
const VINCULAR = require.resolve("../../main/resources/static/vincular.js");

/**
 * Instala document/fetch/location falsos para la página del código dado.
 * El cartel de «Consultando el código…» arranca visible como en el HTML.
 */
function instalarPagina(pathname, resolver) {
    const documento = crearDocumento();
    documento.getElementById("vincular-cargando").hidden = false;
    const fetch = crearFetch(resolver);
    globalThis.document = documento;
    globalThis.fetch = fetch;
    globalThis.location = { pathname, href: "" };
    delete require.cache[VINCULAR];
    return { documento, fetch };
}

/** Confirmación 200 de un código pendiente para el usuario dado. */
function confirmacionDe(usuario) {
    return (url) => {
        if (url.endsWith("/confirmacion")) {
            return respuesta(200, { estado: "pendiente", usuario });
        }
        if (url === "/api/csrf") return respuesta(200, { token: "csrf-t" });
        if (url.endsWith("/aprobar")) return respuesta(200, { estado: "aprobada" });
        throw new Error("llamada no esperada: " + url);
    };
}

test("muestra el namespace del usuario y solo el botón aprueba", async () => {
    const { documento, fetch } = instalarPagina("/vincular/COD9",
            confirmacionDe({ email: "juan@ejemplo.com", namespace: "juan", tipo: "principal" }));

    require(VINCULAR);
    await drenar();

    assert.equal(documento.getElementById("vincular-cargando").hidden, true);
    assert.equal(documento.getElementById("vincular-usuario").textContent, "juan");
    assert.equal(documento.getElementById("vincular-confirmar").hidden, false,
            "la confirmación se ofrece");
    assert.ok(!fetch.llamadas.some((l) => l.url.endsWith("/aprobar")),
            "abrir la página no aprueba: la acción es explícita");

    await documento.getElementById("vincular-aprobar").emit("click");
    await drenar();

    const aprobacion = fetch.llamadas.find((l) => l.url.endsWith("/aprobar"));
    assert.ok(aprobacion, "el botón sí aprueba");
    assert.equal(aprobacion.opciones.method, "POST");
    assert.equal(aprobacion.opciones.headers["X-XSRF-TOKEN"], "csrf-t",
            "la aprobación lleva el token CSRF");
    assert.equal(documento.getElementById("vincular-confirmar").hidden, true);
    assert.equal(documento.getElementById("vincular-listo").hidden, false);
});

test("sin namespace elegido muestra el email", async () => {
    const { documento } = instalarPagina("/vincular/COD9",
            confirmacionDe({ email: "ana@ejemplo.com", namespace: "", tipo: "principal" }));

    require(VINCULAR);
    await drenar();

    assert.equal(documento.getElementById("vincular-usuario").textContent, "ana@ejemplo.com");
    assert.equal(documento.getElementById("vincular-confirmar").hidden, false);
});

test("una sesión vinculada no puede aprobar otra vinculación", async () => {
    const { documento, fetch } = instalarPagina("/vincular/COD9",
            confirmacionDe({ email: "juan@ejemplo.com", namespace: "juan", tipo: "vinculada" }));

    require(VINCULAR);
    await drenar();

    assert.equal(documento.getElementById("vincular-confirmar").hidden, true,
            "no se ofrece el botón de aprobación");
    const error = documento.getElementById("vincular-error");
    assert.equal(error.hidden, false);
    assert.match(error.textContent, /sesión principal/);
    assert.ok(!fetch.llamadas.some((l) => l.url.endsWith("/aprobar")));
});

test("sin sesión entre la página y la consulta vuelve a entrar por el login", async () => {
    const { documento } = instalarPagina("/vincular/COD9", (url) => {
        if (url.endsWith("/confirmacion")) {
            return respuesta(401, { error: "NOT_AUTHENTICATED",
                message: "Iniciá sesión con Google para aprobar una vinculación." });
        }
        throw new Error("llamada no esperada: " + url);
    });

    require(VINCULAR);
    await drenar();

    assert.equal(location.href, "/vincular/COD9",
            "reentra por la página para que el login vuelva al código");
    assert.equal(documento.getElementById("vincular-confirmar").hidden, true);
});

test("si el servicio no responde informa el problema sin confirmar", async () => {
    const { documento } = instalarPagina("/vincular/COD9", () => {
        throw new Error("red caída");
    });

    require(VINCULAR);
    await drenar();

    const error = documento.getElementById("vincular-error");
    assert.equal(error.hidden, false);
    assert.match(error.textContent, /No se pudo contactar/);
    assert.equal(documento.getElementById("vincular-confirmar").hidden, true);
});

test("una aprobación rechazada informa el motivo y no dispara otra", async () => {
    const { documento } = instalarPagina("/vincular/COD9", (url) => {
        if (url.endsWith("/confirmacion")) {
            return respuesta(200, { estado: "pendiente",
                usuario: { email: "juan@ejemplo.com", namespace: "juan", tipo: "principal" } });
        }
        if (url === "/api/csrf") return respuesta(200, { token: "csrf-t" });
        if (url.endsWith("/aprobar")) {
            return respuesta(404, { error: "LINK_CODE_INVALID",
                message: "El código de vinculación no existe o ya no sirve; pedí uno nuevo." });
        }
        throw new Error("llamada no esperada: " + url);
    });

    require(VINCULAR);
    await drenar();
    await documento.getElementById("vincular-aprobar").emit("click");
    await drenar();

    assert.equal(documento.getElementById("vincular-confirmar").hidden, true);
    const error = documento.getElementById("vincular-error");
    assert.equal(error.hidden, false);
    assert.match(error.textContent, /pedí uno nuevo/);
    assert.equal(documento.getElementById("vincular-listo").hidden, true,
            "el rechazo no muestra la vinculación como lista");
});

test("un código inexistente o vencido muestra el rechazo del servidor", async () => {
    const { documento } = instalarPagina("/vincular/COD9", (url) => {
        if (url.endsWith("/confirmacion")) {
            return respuesta(404, { error: "LINK_CODE_INVALID",
                message: "El código de vinculación no existe o ya no sirve; pedí uno nuevo." });
        }
        throw new Error("llamada no esperada: " + url);
    });

    require(VINCULAR);
    await drenar();

    assert.equal(documento.getElementById("vincular-confirmar").hidden, true);
    const error = documento.getElementById("vincular-error");
    assert.equal(error.hidden, false);
    assert.match(error.textContent, /pedí uno nuevo/);
});

test("un código ya aprobado lo informa sin ofrecer la acción", async () => {
    const { documento, fetch } = instalarPagina("/vincular/COD9", (url) => {
        if (url.endsWith("/confirmacion")) {
            return respuesta(200, { estado: "aprobada",
                usuario: { email: "juan@ejemplo.com", namespace: "juan", tipo: "principal" } });
        }
        throw new Error("llamada no esperada: " + url);
    });

    require(VINCULAR);
    await drenar();

    assert.equal(documento.getElementById("vincular-confirmar").hidden, true);
    assert.equal(documento.getElementById("vincular-listo").hidden, false);
    assert.ok(!fetch.llamadas.some((l) => l.url.endsWith("/aprobar")),
            "ya aprobado no ofrece ni dispara la aprobación");
});
