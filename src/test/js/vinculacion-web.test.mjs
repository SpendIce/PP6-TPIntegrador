/*
 * La pantalla de acceso de la vinculación por QR (issue #22) corre sobre el
 * app.js real con un DOM mínimo (web-stub.mjs) y temporizadores falsos:
 * sin sesión pide el código con su CSRF, el QR codifica la confirmacionUrl
 * devuelta por la API, el estado se consulta cada 2 segundos, el código se
 * renueva antes de vencer y la consulta que lo encuentra aprobado recarga
 * con la sesión vinculada ya abierta.
 *
 * Ejecutar: cd src/test/js && node --test
 */
import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import qrcode from "../../main/resources/static/vendor/qrcode.js";
import QrPng from "../../main/resources/static/qr-code.js";
import VinculacionTimers from "../../main/resources/static/vinculacion-timers.js";
import { decodePngToRgba, decodeQrText } from "./png-decode.mjs";
import { crearDocumento, crearFetch, respuesta, drenar } from "./web-stub.mjs";

const require = createRequire(import.meta.url);
const APP = require.resolve("../../main/resources/static/app.js");

const T0 = Date.parse("2026-10-27T03:00:00Z");
const TTL_MS = 60_000;
const CONFIRMACION = "https://demo.test/vincular/COD1";

// Los mismos globales que el navegador carga con las etiquetas <script>.
globalThis.qrcode = qrcode;
globalThis.QrPng = QrPng;
globalThis.VinculacionTimers = VinculacionTimers;

/**
 * Instala document/fetch/location falsos y los temporizadores en T0.
 * `resolver` decide la respuesta falsa de cada llamada fetch.
 */
function instalarEntorno(t, resolver) {
    t.mock.timers.enable({ apis: ["setTimeout", "setInterval", "Date"], now: T0 });
    const documento = crearDocumento();
    const fetch = crearFetch(resolver);
    const recargas = { total: 0 };
    const blobs = [];
    globalThis.document = documento;
    globalThis.fetch = fetch;
    globalThis.location = { pathname: "/", href: "", reload: () => recargas.total++ };
    URL.createObjectURL = (blob) => {
        blobs.push(blob);
        return "blob:qr-" + blobs.length;
    };
    URL.revokeObjectURL = () => {};
    delete require.cache[APP];
    return { documento, fetch, recargas, blobs };
}

/**
 * Resolver base del flujo: sin sesión en /api/sesion, CSRF válido y cada
 * POST /api/vinculacion crea un código que vence a los 60 s contra el
 * reloj falso. `pedidos` cuenta los códigos creados y `consultas`
 * registra los códigos consultados en orden.
 */
function resolverBase(pedidos, consultas, responderEstado) {
    return (url, opciones = {}) => {
        if (url === "/api/sesion") return respuesta(200, { usuario: null });
        if (url === "/api/csrf") return respuesta(200, { token: "csrf-t" });
        if (url === "/api/vinculacion" && opciones.method === "POST") {
            pedidos.total++;
            const codigo = "COD" + pedidos.total;
            return respuesta(201, {
                codigo,
                confirmacionUrl: "https://demo.test/vincular/" + codigo,
                expiresAt: new Date(Date.now() + TTL_MS).toISOString()
            }, { date: new Date(Date.now()).toUTCString() });
        }
        const estado = url.match(/^\/api\/vinculacion\/([A-Z0-9]+)$/);
        if (estado) {
            consultas.push(estado[1]);
            return responderEstado(estado[1], consultas.length);
        }
        throw new Error("llamada no esperada: " + url);
    };
}

test("el QR de la pantalla de acceso codifica la confirmacionUrl devuelta por la API", async (t) => {
    const { documento, fetch, blobs } = instalarEntorno(t,
            resolverBase({ total: 0 }, [], () => respuesta(200, { estado: "pendiente" })));

    require(APP);
    await drenar();

    assert.equal(documento.getElementById("vinculacion").hidden, false,
            "sin sesión se ofrece la vinculación");
    const pedido = fetch.llamadas.find((l) => l.url === "/api/vinculacion");
    assert.equal(pedido.opciones.method, "POST");
    assert.equal(pedido.opciones.headers["X-XSRF-TOKEN"], "csrf-t",
            "el pedido del código lleva el token CSRF");

    const qr = documento.getElementById("vinculacion-qr");
    assert.ok(qr.src.startsWith("blob:"), "el QR quedó con su object URL");
    const bytes = new Uint8Array(await blobs[0].arrayBuffer());
    const { rgba, width, height } = decodePngToRgba(bytes);
    assert.equal(decodeQrText(rgba, width, height), CONFIRMACION,
            "el PNG del QR codifica la URL de confirmación del código");
});

test("consulta el estado cada 2 segundos y recarga cuando encuentra aprobada", async (t) => {
    const consultas = [];
    const { recargas } = instalarEntorno(t,
            resolverBase({ total: 0 }, consultas,
                    (codigo, consulta) => respuesta(200,
                            { estado: consulta < 3 ? "pendiente" : "aprobada" })));

    require(APP);
    await drenar();
    assert.equal(consultas.length, 0, "el código recién pedido aún no se consulta");

    t.mock.timers.tick(2000);
    await drenar();
    assert.deepEqual(consultas, ["COD1"], "la primera consulta sale a los 2 s");

    t.mock.timers.tick(2000);
    await drenar();
    assert.deepEqual(consultas, ["COD1", "COD1"], "la segunda sale a los 4 s");
    assert.equal(recargas.total, 0, "pendiente no recarga");

    t.mock.timers.tick(2000);
    await drenar();
    assert.deepEqual(consultas, ["COD1", "COD1", "COD1"]);
    assert.equal(recargas.total, 1,
            "la consulta que encuentra aprobada recarga con la sesión vinculada");

    t.mock.timers.tick(10_000);
    await drenar();
    assert.equal(consultas.length, 3, "consumido el código ya no se consulta");
});

test("el código se renueva antes de su vencimiento y el estado consulta el vigente", async (t) => {
    const pedidos = { total: 0 };
    const consultas = [];
    instalarEntorno(t, resolverBase(pedidos, consultas,
            () => respuesta(200, { estado: "pendiente" })));

    require(APP);
    await drenar();
    assert.equal(pedidos.total, 1);

    t.mock.timers.tick(54_999);
    await drenar();
    assert.equal(pedidos.total, 1, "un instante antes de renovar sigue el mismo código");

    t.mock.timers.tick(1);
    await drenar();
    assert.equal(pedidos.total, 2,
            "a los 55 s pide el código nuevo, antes del vencimiento de 60 s");

    t.mock.timers.tick(2000);
    await drenar();
    assert.equal(consultas.at(-1), "COD2",
            "la consulta siguiente usa el código vigente");
});

test("un QR que no se puede generar informa el error y reintenta sin consultar", async (t) => {
    let fallas = 1;
    globalThis.QrPng = {
        ...QrPng,
        encode: (factory, texto) => {
            if (fallas-- > 0) throw new Error("png fallido");
            return QrPng.encode(factory, texto);
        }
    };
    t.after(() => { globalThis.QrPng = QrPng; });

    const pedidos = { total: 0 };
    const consultas = [];
    const { documento } = instalarEntorno(t, resolverBase(pedidos, consultas,
            () => respuesta(200, { estado: "pendiente" })));

    require(APP);
    await drenar();
    assert.equal(pedidos.total, 1);

    const estado = documento.getElementById("vinculacion-estado");
    assert.match(estado.textContent, /QR/, "el fallo del QR se informa en la pantalla");
    assert.equal(documento.getElementById("vinculacion-qr").src, "",
            "sin PNG el QR no queda con una imagen rota");

    t.mock.timers.tick(4000);
    await drenar();
    assert.equal(consultas.length, 0,
            "no se consulta el estado de un código que nadie puede escanear");

    t.mock.timers.tick(1000);
    await drenar();
    assert.equal(pedidos.total, 2, "a los 5 s reintenta con un código nuevo");
    assert.ok(documento.getElementById("vinculacion-qr").src.startsWith("blob:"),
            "el reintento sí genera el QR");
    assert.equal(estado.textContent, "");

    t.mock.timers.tick(2000);
    await drenar();
    assert.deepEqual(consultas, ["COD2"], "el código nuevo sí se consulta");
});

test("una consulta que encuentra el código inválido pide otro", async (t) => {
    const pedidos = { total: 0 };
    instalarEntorno(t, resolverBase(pedidos, [],
            () => respuesta(404, { error: "LINK_CODE_INVALID",
                message: "El código de vinculación no existe o ya no sirve; pedí uno nuevo." })));

    require(APP);
    await drenar();
    assert.equal(pedidos.total, 1);

    t.mock.timers.tick(2000);
    await drenar();
    assert.equal(pedidos.total, 2, "el 404 del estado dispara un código nuevo");
});

test("un estado vencida reemplaza el código sin esperar a la renovación", async (t) => {
    const pedidos = { total: 0 };
    instalarEntorno(t, resolverBase(pedidos, [],
            () => respuesta(200, { estado: "vencida" })));

    require(APP);
    await drenar();
    assert.equal(pedidos.total, 1);

    t.mock.timers.tick(2000);
    await drenar();
    assert.equal(pedidos.total, 2, "la consulta vencida pide un código nuevo");
});
