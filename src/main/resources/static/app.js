const form = document.getElementById("shorten-form");
const input = document.getElementById("destination");
const result = document.getElementById("result");
const errorBox = document.getElementById("error");
const shortUrl = document.getElementById("short-url");
const expiresAt = document.getElementById("expires-at");
const reuseNotice = document.getElementById("reuse-notice");
const qrImage = document.getElementById("qr-image");
const qrDownload = document.getElementById("qr-download");

form.addEventListener("submit", async (event) => {
    event.preventDefault();
    errorBox.hidden = true;
    result.hidden = true;

    let response;
    try {
        response = await fetch("/api/links", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ destination: input.value })
        });
    } catch (e) {
        showError("No se pudo contactar al servicio.");
        return;
    }

    const body = await response.json().catch(() => null);
    if (!response.ok) {
        showError(body && body.message ? body.message : "La dirección fue rechazada.");
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
