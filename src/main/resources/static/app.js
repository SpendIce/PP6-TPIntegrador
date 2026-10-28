const form = document.getElementById("shorten-form");
const input = document.getElementById("destination");
const result = document.getElementById("result");
const errorBox = document.getElementById("error");
const shortUrl = document.getElementById("short-url");
const expiresAt = document.getElementById("expires-at");
const reuseNotice = document.getElementById("reuse-notice");
const qrImage = document.getElementById("qr-image");
const qrDownload = document.getElementById("qr-download");
const cuentaEntrar = document.getElementById("cuenta-entrar");
const cuentaUsuario = document.getElementById("cuenta-usuario");
const cuentaCerrarVinculadas = document.getElementById("cuenta-cerrar-vinculadas");
const cuentaSalir = document.getElementById("cuenta-salir");
const cuentaMensaje = document.getElementById("cuenta-mensaje");
const namespaceSection = document.getElementById("namespace");
const namespaceForm = document.getElementById("namespace-form");
const namespaceInput = document.getElementById("namespace-input");
const namespaceError = document.getElementById("namespace-error");
const namespaceTitle = document.getElementById("namespace-title");
const namespaceSubmit = document.getElementById("namespace-submit");
const miNamespace = document.getElementById("mi-namespace");
const namespaceActual = document.getElementById("namespace-actual");
const namespacesAnteriores = document.getElementById("namespaces-anteriores");
const aliasPersonal = document.getElementById("alias-personal");
const aliasInput = document.getElementById("alias-personalizado");
const vinculacionSection = document.getElementById("vinculacion");
const vinculacionQr = document.getElementById("vinculacion-qr");
const vinculacionEstado = document.getElementById("vinculacion-estado");

// Sesión actual (issue #19): sin sesión se ofrece el acceso con Google;
// con sesión principal se muestra el email del usuario y el cierre. Si
// todavía no eligió namespace, se abre la pantalla del primer login
// (issue #20) con la sugerencia que calcula el servidor.
(async () => {
    try {
        const respuesta = await fetch("/api/sesion", {
            headers: { "Accept": "application/json" }
        });
        if (!respuesta.ok) return;
        const sesion = await respuesta.json();
        if (!sesion.usuario) {
            cuentaEntrar.hidden = false;
            // Sin sesión, la pantalla de acceso ofrece la vinculación por
            // QR (issue #22): el celular con sesión principal la aprueba.
            iniciarVinculacion();
            return;
        }
        mostrarUsuario(sesion.usuario);
        cuentaSalir.hidden = false;
        cuentaCerrarVinculadas.hidden = sesion.usuario.tipo !== "principal";
        if (!sesion.usuario.namespace) {
            namespaceInput.value = sesion.usuario.sugerencia || "";
            namespaceSection.hidden = false;
        } else {
            // Con namespace elegido el formulario suma el campo opcional
            // del alias personalizado (issue #21).
            aliasPersonal.hidden = false;
            namespaceTitle.textContent = "Cambiar mi namespace";
            namespaceSubmit.textContent = "Cambiar";
            namespaceSection.hidden = false;
            miNamespace.hidden = false;
            namespaceActual.textContent = "Actual: " + sesion.usuario.namespace;
            cargarNamespacesAnteriores();
        }
    } catch (error) {
        console.warn("No se pudo consultar la sesión actual", error);
    }
})();

namespaceForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    namespaceError.hidden = true;

    // El valor se captura antes del await del token CSRF, como en el
    // formulario de enlaces: se envía lo que el usuario confirmó.
    const propuesta = namespaceInput.value;
    let respuesta;
    try {
        const csrfResponse = await fetch("/api/csrf");
        const csrf = csrfResponse.ok ? await csrfResponse.json() : null;
        if (!csrf || typeof csrf.token !== "string" || !csrf.token) {
            throw new Error("sin token csrf");
        }
        respuesta = await fetch("/api/namespace", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "X-XSRF-TOKEN": csrf.token
            },
            body: JSON.stringify({ namespace: propuesta })
        });
    } catch (error) {
        showNamespaceError("No se pudo contactar al servicio.");
        return;
    }

    const body = await respuesta.json().catch(() => null);
    if (!respuesta.ok) {
        // Cada rechazo del contrato trae {error, message}: se muestra el
        // mensaje legible sin inventar el motivo.
        showNamespaceError(body && body.message
                ? body.message : "Ese namespace no se pudo elegir.");
        return;
    }
    mostrarUsuario(body.usuario);
    namespaceInput.value = "";
    namespaceTitle.textContent = "Cambiar mi namespace";
    namespaceSubmit.textContent = "Cambiar";
    namespaceActual.textContent = "Actual: " + body.usuario.namespace;
    miNamespace.hidden = false;
    cargarNamespacesAnteriores();
    // Recién elegido el namespace ya se pueden crear enlaces personales.
    aliasPersonal.hidden = false;
});

async function cargarNamespacesAnteriores() {
    namespacesAnteriores.replaceChildren();
    try {
        const respuesta = await fetch("/api/namespace/anteriores", {
            headers: { "Accept": "application/json" }
        });
        if (!respuesta.ok) return;
        const body = await respuesta.json();
        NamespaceGracia.renderAnteriores(
                document, namespacesAnteriores, body.namespaces, Date.now(),
                (namespace) => {
                    namespaceInput.value = namespace;
                    namespaceForm.requestSubmit();
                });
    } catch (error) {
        console.warn("No se pudieron consultar los namespaces anteriores", error);
    }
}

function actualizarTiempoRestante() {
    for (const elemento of namespacesAnteriores.querySelectorAll(".grace-remaining")) {
        elemento.textContent = NamespaceGracia.tiempoRestante(
                elemento.dataset.endsAt, Date.now());
    }
}

setInterval(actualizarTiempoRestante, 30000);

function mostrarUsuario(usuario) {
    cuentaUsuario.textContent = usuario.namespace
            ? usuario.email + " · " + usuario.namespace
            : usuario.email;
    cuentaUsuario.hidden = false;
}

function showNamespaceError(message) {
    namespaceError.textContent = message;
    namespaceError.hidden = false;
    namespaceInput.focus();
}

cuentaSalir.addEventListener("click", async () => {
    // El logout exige CSRF igual que el POST de enlaces (issue #18). Si no
    // se pudo cerrar, la sesión sigue viva: se muestra el error y no se
    // recarga como si hubiera funcionado.
    try {
        const csrfResponse = await fetch("/api/csrf");
        const csrf = csrfResponse.ok ? await csrfResponse.json() : null;
        if (!csrf || !csrf.token) {
            showError("No se pudo cerrar la sesión: falta el token CSRF.");
            return;
        }
        const cierre = await fetch("/api/logout", {
            method: "POST",
            headers: { "X-XSRF-TOKEN": csrf.token }
        });
        if (!cierre.ok) {
            showError("No se pudo cerrar la sesión. Probá de nuevo.");
            return;
        }
        location.reload();
    } catch (error) {
        showError("No se pudo cerrar la sesión. Probá de nuevo.");
    }
});

cuentaCerrarVinculadas.addEventListener("click", async () => {
    try {
        const csrfResponse = await fetch("/api/csrf");
        const csrf = csrfResponse.ok ? await csrfResponse.json() : null;
        if (!csrf || !csrf.token) {
            showError("No se pudieron cerrar las sesiones vinculadas: falta el token CSRF.");
            return;
        }
        const cierre = await fetch("/api/sesiones-vinculadas/cerrar", {
            method: "POST",
            headers: { "X-XSRF-TOKEN": csrf.token }
        });
        if (!cierre.ok) {
            showError("No se pudieron cerrar las sesiones vinculadas. Probá de nuevo.");
            return;
        }
        cuentaMensaje.textContent = "Se cerraron todas las sesiones vinculadas.";
        cuentaMensaje.hidden = false;
    } catch (error) {
        showError("No se pudieron cerrar las sesiones vinculadas. Probá de nuevo.");
    }
});

form.addEventListener("submit", async (event) => {
    event.preventDefault();
    errorBox.hidden = true;
    result.hidden = true;

    // El destino se captura antes del primer await: si el usuario edita el
    // campo mientras llega el token CSRF, se acorta lo que envió al pulsar
    // «Acortar», no lo que quedó escrito después.
    const destination = input.value;
    // Vacío (o el campo oculto sin sesión/namespace) pide el enlace
    // acortado de siempre; el servidor lo trata igual que omitirlo.
    const aliasPersonalizado = aliasPersonal.hidden ? "" : aliasInput.value;

    let response;
    try {
        // CSRF (issue #18): el POST exige el token de GET /api/csrf,
        // repetido en X-XSRF-TOKEN; la cookie viaja sola por ser mismo
        // origen.
        const csrfResponse = await fetch("/api/csrf");
        const csrf = csrfResponse.ok ? await csrfResponse.json() : null;
        if (!csrf || typeof csrf.token !== "string" || !csrf.token) {
            throw new Error("sin token csrf");
        }
        response = await fetch("/api/links", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "X-XSRF-TOKEN": csrf.token
            },
            body: JSON.stringify({ destination, aliasPersonalizado })
        });
    } catch (e) {
        showError("No se pudo contactar al servicio.");
        return;
    }

    const body = await response.json().catch(() => null);
    if (!response.ok) {
        showError(body && body.message ? body.message : "La dirección fue rechazada.");
        // Los rechazos del alias personalizado apuntan a su propio campo.
        if (body && (body.error === "ALIAS_INVALID" || body.error === "ALIAS_TAKEN")) {
            aliasInput.focus();
        }
        return;
    }

    shortUrl.textContent = body.shortUrl;
    shortUrl.href = body.shortUrl;
    expiresAt.textContent = new Date(body.expiresAt).toLocaleString();
    expiresAt.dateTime = body.expiresAt;
    reuseNotice.textContent = body.reuseNotice;
    renderQr(body);
    result.hidden = false;
});

function showError(message) {
    errorBox.textContent = message;
    errorBox.hidden = false;
    // El campo conserva el texto ingresado y recupera el foco para corregirlo.
    input.focus();
}

// ---------------------------------------------------------------------
// QR del enlace (issue #7): se genera en el cliente sobre el `shortUrl`
// devuelto por la API — la dirección pública del enlace, no el destino —
// y se muestra y descarga como PNG sin pasar por el backend.
// ---------------------------------------------------------------------

let currentQrUrl = null;

function renderQr(body) {
    if (currentQrUrl !== null) {
        URL.revokeObjectURL(currentQrUrl);
        currentQrUrl = null;
    }
    currentQrUrl = pngDeQr(body.shortUrl, "No se pudo generar el QR del enlace.");
    if (currentQrUrl === null) {
        // El enlace y su vencimiento siguen siendo útiles aunque el QR falle.
        qrImage.removeAttribute("src");
        qrDownload.removeAttribute("href");
        return;
    }
    qrImage.src = currentQrUrl;
    qrDownload.href = currentQrUrl;
    qrDownload.download = QrPng.suggestedFileName(body.alias);
}

// PNG del QR como object URL con el módulo vendored; null si falla.
function pngDeQr(texto, mensajeError) {
    try {
        const pngBytes = QrPng.encode(qrcode, texto);
        return URL.createObjectURL(new Blob([pngBytes], { type: "image/png" }));
    } catch (e) {
        console.error(mensajeError, e);
        return null;
    }
}

// ---------------------------------------------------------------------
// Vinculación por QR (issue #22): el dispositivo que espera pide un
// código de un solo uso, muestra el QR con su URL de confirmación y
// consulta el estado cada 2 segundos. El código vence a los 60
// segundos, así que se renueva unos segundos antes de que pase. La
// consulta que encuentra el código aprobado ya abrió la sesión vinculada
// en el servidor: recargar deja a la web con ella. Por eso la renovación
// espera a la consulta en vuelo antes de reemplazar el código —si trajo
// la aprobación, el código se preserva— y una aprobación se honra aunque
// llegue con el código ya rotado. Mientras el reemplazo se pide, el QR
// que sigue en pantalla conserva sus consultas: nunca se muestra un
// código cuya aprobación no pueda consumirse.
// ---------------------------------------------------------------------

const VINCULACION_CONSULTA_MS = 2000;
// Margen para que el QR nuevo llegue antes de que el anterior venza.
const VINCULACION_RENOVACION_MS = 5000;

let vinculacionCodigo = null;
let vinculacionTimer = null;
let vinculacionRenovacion = null;
let vinculacionQrUrl = null;
// La consulta del estado en vuelo: la renovación la espera antes de
// reemplazar el código, por si trae la aprobación.
let vinculacionConsulta = null;
// Queda cuando una consulta encontró el código aprobado: la sesión
// vinculada ya se abrió y no se piden más códigos.
let vinculacionAprobada = false;
// Cada pedido de código invalida al anterior que siga esperando
// respuestas, para que no pise al vigente al completar.
let vinculacionGeneracion = 0;

function iniciarVinculacion() {
    vinculacionSection.hidden = false;
    pedirCodigoVinculacion();
}

function esperar(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

async function pedirCodigoVinculacion() {
    // Solo la renovación programada se frena: el código que sigue en el
    // QR conserva sus consultas mientras se pide el reemplazo, porque su
    // aprobación todavía puede abrir la sesión.
    clearTimeout(vinculacionRenovacion);
    const generacion = ++vinculacionGeneracion;
    if (vinculacionAprobada) {
        return;
    }

    // Una consulta en vuelo puede traer la aprobación que ya abrió la
    // sesión vinculada: se espera —acotado a una consulta más— antes de
    // reemplazar el código. Si llegó aprobada, la consulta misma recarga
    // y este pedido no sale: el código se preserva.
    const enVuelo = vinculacionConsulta;
    if (enVuelo !== null) {
        await Promise.race([enVuelo.catch(() => null),
                esperar(VINCULACION_CONSULTA_MS)]);
        if (vinculacionAprobada || generacion !== vinculacionGeneracion) {
            return;
        }
    }

    let creado;
    let alta = null;
    try {
        const csrfResponse = await fetch("/api/csrf");
        const csrf = csrfResponse.ok ? await csrfResponse.json() : null;
        if (!csrf || typeof csrf.token !== "string" || !csrf.token) {
            throw new Error("sin token csrf");
        }
        alta = await fetch("/api/vinculacion", {
            method: "POST",
            headers: { "X-XSRF-TOKEN": csrf.token }
        });
        creado = alta.ok ? await alta.json() : null;
        if (!creado) {
            throw new Error("creación rechazada");
        }
    } catch (error) {
        if (vinculacionAprobada || generacion !== vinculacionGeneracion) {
            return;
        }
        // El código vigente sigue consultándose durante el reintento.
        vinculacionEstado.textContent =
                "No se pudo pedir el código de vinculación; reintentá.";
        vinculacionRenovacion = setTimeout(pedirCodigoVinculacion,
                VINCULACION_RENOVACION_MS);
        return;
    }
    if (vinculacionAprobada || generacion !== vinculacionGeneracion) {
        // Mientras se pedía, otra renovación ganó o una consulta
        // encontró la aprobación: este código queda sin uso y vence solo.
        return;
    }

    if (!renderQrVinculacion(creado.confirmacionUrl)) {
        // Sin QR nuevo no hay cómo aprobar este código, pero el anterior
        // sigue vigente con sus consultas: se informa y se reintenta.
        vinculacionEstado.textContent =
                "No se pudo generar el QR de vinculación; reintentando.";
        vinculacionRenovacion = setTimeout(pedirCodigoVinculacion,
                VINCULACION_RENOVACION_MS);
        return;
    }

    // Recién con el QR nuevo en pantalla el código anterior deja de
    // consultarse y pasa a ser el vigente.
    clearInterval(vinculacionTimer);
    vinculacionCodigo = creado.codigo;
    vinculacionEstado.textContent = "";

    vinculacionTimer = setInterval(
            () => consultarEstadoVinculacion(creado.codigo), VINCULACION_CONSULTA_MS);
    // Contra el reloj del servidor y con piso de una consulta: la lógica
    // vive en vinculacion-timers.js para poder probarla con node --test.
    vinculacionRenovacion = setTimeout(pedirCodigoVinculacion,
            VinculacionTimers.proximaRenovacionMs(creado.expiresAt,
                    alta?.headers?.get("date"),
                    VINCULACION_CONSULTA_MS, VINCULACION_RENOVACION_MS));
}

function consultarEstadoVinculacion(codigo) {
    if (codigo !== vinculacionCodigo) {
        return;
    }
    // La consulta queda registrada para que la renovación la espere: si
    // trae la aprobación, el código se preserva en vez de pedirse otro.
    const consulta = ejecutarConsultaVinculacion(codigo);
    vinculacionConsulta = consulta;
    const limpiar = () => {
        if (vinculacionConsulta === consulta) {
            vinculacionConsulta = null;
        }
    };
    consulta.then(limpiar, limpiar);
}

async function ejecutarConsultaVinculacion(codigo) {
    let estado;
    try {
        const respuesta = await fetch("/api/vinculacion/" + encodeURIComponent(codigo), {
            headers: { "Accept": "application/json" }
        });
        if (respuesta.status === 404) {
            // El código quedó inválido: un QR nuevo lo reemplaza, salvo
            // que otro pedido ya lo haya renovado.
            if (codigo === vinculacionCodigo) {
                pedirCodigoVinculacion();
            }
            return;
        }
        estado = respuesta.ok ? await respuesta.json() : null;
    } catch (error) {
        return;
    }
    if (!estado) {
        return;
    }
    if (estado.estado === "aprobada") {
        // El servidor ya consumió el código y esta misma respuesta abrió
        // la sesión vinculada: se completa recargando aunque el código
        // haya rotado mientras la consulta viajaba.
        vinculacionAprobada = true;
        clearInterval(vinculacionTimer);
        clearTimeout(vinculacionRenovacion);
        vinculacionCodigo = null;
        location.reload();
        return;
    }
    if (estado.estado === "vencida" && codigo === vinculacionCodigo) {
        pedirCodigoVinculacion();
    }
    // `pendiente` y las respuestas de un código que ya rotó no cambian
    // nada: la siguiente consulta del intervalo vuelve a mirar.
}

// true si el QR de vinculación quedó mostrado; false si no se pudo
// generar, para que el flujo se detenga y reintente en vez de consultar
// un código que nadie puede escanear.
function renderQrVinculacion(confirmacionUrl) {
    if (vinculacionQrUrl !== null) {
        URL.revokeObjectURL(vinculacionQrUrl);
        vinculacionQrUrl = null;
    }
    // El QR codifica la URL de confirmación que devuelve la API, con el
    // mismo módulo que el QR del enlace acortado.
    vinculacionQrUrl = pngDeQr(confirmacionUrl,
            "No se pudo generar el QR de vinculación.");
    if (vinculacionQrUrl === null) {
        vinculacionQr.removeAttribute("src");
        return false;
    }
    vinculacionQr.src = vinculacionQrUrl;
    return true;
}
