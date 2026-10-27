/*
 * Entorno mínimo para ejecutar los scripts de la web (app.js, vincular.js)
 * bajo `node --test` sin navegador: un `document` con elementos por id, un
 * `fetch` que enruta respuestas falsas y un `location` manipulable. Cada
 * script se carga con `require` después de instalar los globales, como hace
 * el navegador con las etiquetas <script>.
 */

/** Elemento con lo que los scripts tocan: visibilidad, texto y eventos. */
export function crearElemento() {
    const listeners = new Map();
    return {
        hidden: true,
        textContent: "",
        value: "",
        disabled: false,
        src: "",
        href: "",
        download: "",
        dateTime: "",
        listeners,
        addEventListener(tipo, fn) {
            if (!listeners.has(tipo)) listeners.set(tipo, []);
            listeners.get(tipo).push(fn);
        },
        async emit(tipo, evento = {}) {
            for (const fn of listeners.get(tipo) || []) {
                await fn(evento);
            }
        },
        removeAttribute() {},
        focus() {}
    };
}

/** `document.getElementById` que crea el elemento la primera vez. */
export function crearDocumento() {
    const elementos = new Map();
    return {
        elementos,
        getElementById(id) {
            if (!elementos.has(id)) elementos.set(id, crearElemento());
            return elementos.get(id);
        }
    };
}

/** Respuesta fetch con lo que usan los scripts: ok, status, json y headers. */
export function respuesta(status, body, headers = {}) {
    return {
        ok: status >= 200 && status < 300,
        status,
        headers: { get: (nombre) => headers[nombre.toLowerCase()] ?? null },
        json: async () => body
    };
}

/**
 * `fetch` que registra cada llamada y delega en `resolver(url, opciones)`,
 * que devuelve la `respuesta` a entregar (o lanza para simular red caída).
 */
export function crearFetch(resolver) {
    const llamadas = [];
    const fetch = async (url, opciones = {}) => {
        llamadas.push({ url, opciones });
        return resolver(url, opciones);
    };
    fetch.llamadas = llamadas;
    return fetch;
}

/** Drena microtareas: las continuaciones de los fetch falsos corren acá. */
export async function drenar() {
    for (let i = 0; i < 10; i++) {
        await new Promise((resolve) => setImmediate(resolve));
    }
}
