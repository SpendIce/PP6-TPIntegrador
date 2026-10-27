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
const cuentaSalir = document.getElementById("cuenta-salir");
const namespaceSection = document.getElementById("namespace");
const namespaceForm = document.getElementById("namespace-form");
const namespaceInput = document.getElementById("namespace-input");
const namespaceError = document.getElementById("namespace-error");
const aliasPersonal = document.getElementById("alias-personal");
const aliasInput = document.getElementById("alias-personalizado");

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
            return;
        }
        mostrarUsuario(sesion.usuario);
        cuentaSalir.hidden = false;
        if (!sesion.usuario.namespace) {
            namespaceInput.value = sesion.usuario.sugerencia || "";
            namespaceSection.hidden = false;
        } else {
            // Con namespace elegido el formulario suma el campo opcional
            // del alias personalizado (issue #21).
            aliasPersonal.hidden = false;
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
    namespaceSection.hidden = true;
    // Recién elegido el namespace ya se pueden crear enlaces personales.
    aliasPersonal.hidden = false;
});

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
    try {
        const pngBytes = QrPng.encode(qrcode, body.shortUrl);
        currentQrUrl = URL.createObjectURL(new Blob([pngBytes], { type: "image/png" }));
        qrImage.src = currentQrUrl;
        qrDownload.href = currentQrUrl;
        qrDownload.download = QrPng.suggestedFileName(body.alias);
    } catch (e) {
        // El enlace y su vencimiento siguen siendo útiles aunque el QR falle.
        qrImage.removeAttribute("src");
        qrDownload.removeAttribute("href");
        console.error("No se pudo generar el QR del enlace.", e);
    }
}
