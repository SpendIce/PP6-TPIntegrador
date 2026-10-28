// Vinculación por QR (issue #22): la página /vincular/{codigo} confirma
// el código que el QR codificó. Muestra el usuario de la sesión
// (namespace o email) y solo el botón aprueba: abrir la página no
// confirma nada. Desde una sesión vinculada informa que no puede
// aprobar otra vinculación; un código vencido o inexistente pide uno
// nuevo.
const cargando = document.getElementById("vincular-cargando");
const confirmar = document.getElementById("vincular-confirmar");
const usuarioNombre = document.getElementById("vincular-usuario");
const aprobarBoton = document.getElementById("vincular-aprobar");
const listo = document.getElementById("vincular-listo");
const errorBox = document.getElementById("vincular-error");

// El código viaja en la ruta: la página llega por forward y conserva la
// URL original /vincular/{codigo}.
const codigo = decodeURIComponent(location.pathname.split("/").pop() || "");

(async () => {
    if (!codigo || codigo === "vincular") {
        mostrarError("El código de vinculación no existe o ya no sirve; pedí uno nuevo.");
        return;
    }

    let datos;
    try {
        const respuesta = await fetch(
                "/api/vinculacion/" + encodeURIComponent(codigo) + "/confirmacion",
                { headers: { "Accept": "application/json" } });
        if (respuesta.status === 401) {
            // La sesión se perdió entre la página y la consulta: entrar
            // de nuevo por la página rehace el login con el retorno acá.
            location.href = "/vincular/" + encodeURIComponent(codigo);
            return;
        }
        const body = await respuesta.json().catch(() => null);
        if (!respuesta.ok) {
            mostrarError(body && body.message
                    ? body.message
                    : "El código de vinculación no existe o ya no sirve; pedí uno nuevo.");
            return;
        }
        datos = body;
    } catch (error) {
        mostrarError("No se pudo contactar al servicio.");
        return;
    }

    cargando.hidden = true;
    if (datos.usuario.tipo !== "principal") {
        mostrarError("Esta sesión no puede aprobar una vinculación:"
                + " se necesita una sesión principal.");
        return;
    }
    if (datos.estado === "aprobada") {
        listo.textContent = "La vinculación ya quedó aprobada;"
                + " el otro dispositivo está quedando conectado.";
        listo.hidden = false;
        return;
    }

    // La confirmación muestra el namespace si el usuario lo eligió y el
    // email si no; la aprobación queda solo en el botón.
    usuarioNombre.textContent = datos.usuario.namespace || datos.usuario.email;
    confirmar.hidden = false;
})();

aprobarBoton.addEventListener("click", async () => {
    aprobarBoton.disabled = true;
    try {
        const csrfResponse = await fetch("/api/csrf");
        const csrf = csrfResponse.ok ? await csrfResponse.json() : null;
        if (!csrf || typeof csrf.token !== "string" || !csrf.token) {
            throw new Error("sin token csrf");
        }
        const respuesta = await fetch(
                "/api/vinculacion/" + encodeURIComponent(codigo) + "/aprobar",
                { method: "POST", headers: { "X-XSRF-TOKEN": csrf.token } });
        const body = await respuesta.json().catch(() => null);
        if (!respuesta.ok) {
            confirmar.hidden = true;
            mostrarError(body && body.message
                    ? body.message
                    : "El código de vinculación no existe o ya no sirve; pedí uno nuevo.");
            return;
        }
        confirmar.hidden = true;
        listo.textContent = "Listo: el otro dispositivo está quedando conectado.";
        listo.hidden = false;
    } catch (error) {
        confirmar.hidden = true;
        mostrarError("No se pudo contactar al servicio.");
    } finally {
        aprobarBoton.disabled = false;
    }
});

function mostrarError(message) {
    cargando.hidden = true;
    errorBox.textContent = message;
    errorBox.hidden = false;
}
