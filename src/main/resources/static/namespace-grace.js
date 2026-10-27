/*
 * Namespaces anteriores en gracia (issue #24): formatea el tiempo que le
 * queda a cada reserva y arma la lista de «Mi namespace» con su botón
 * «Volver a este». Vive fuera de app.js para que las pruebas de Node
 * (`src/test/js/`) ejerciten el mismo cálculo y el mismo render que ve el
 * usuario.
 *
 * Expone `NamespaceGracia` como global en el navegador y como
 * `module.exports` bajo CommonJS.
 */
const NamespaceGracia = (function () {
    "use strict";

    // Milisegundos restantes hasta `endsAt`, redondeados hacia arriba en
    // minutos: una reserva a la que le quedan 30 segundos se muestra como
    // «1 min» y nunca como un tiempo negativo.
    function tiempoRestante(endsAt, ahora) {
        const milisegundos = Math.max(0, Date.parse(endsAt) - ahora);
        const minutos = Math.ceil(milisegundos / 60000);
        const horas = Math.floor(minutos / 60);
        const minutosRestantes = minutos % 60;
        return horas ? `${horas} h ${minutosRestantes} min` : `${minutos} min`;
    }

    // Reemplaza el contenido de `contenedor` con un ítem por namespace
    // anterior: nombre, tiempo restante y botón que invoca `alVolver` con
    // el nombre elegido. `documento` se recibe para poder probarlo sin
    // navegador.
    function renderAnteriores(documento, contenedor, anteriores, ahora, alVolver) {
        contenedor.replaceChildren();
        for (const anterior of anteriores) {
            const item = documento.createElement("li");
            const nombre = documento.createElement("strong");
            nombre.textContent = anterior.namespace;
            const restante = documento.createElement("span");
            restante.className = "grace-remaining";
            restante.dataset.endsAt = anterior.graceEndsAt;
            restante.textContent = tiempoRestante(anterior.graceEndsAt, ahora);
            const volver = documento.createElement("button");
            volver.type = "button";
            volver.textContent = "Volver a este";
            volver.addEventListener("click", () => alVolver(anterior.namespace));
            item.append(nombre, " · ", restante, " ", volver);
            contenedor.append(item);
        }
    }

    return {
        tiempoRestante: tiempoRestante,
        renderAnteriores: renderAnteriores
    };
})();

if (typeof module !== "undefined" && module.exports) {
    module.exports = NamespaceGracia;
}
