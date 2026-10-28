/*
 * Aviso del namespace anterior durante la gracia (issue #25): la página
 * trae la nueva dirección en el enlace «Ir ahora» y la demora ya
 * renderizada en la cuenta regresiva; este script descuenta un segundo
 * por tick, navega a la nueva dirección al llegar a cero y copia la
 * dirección con el botón. El meta refresh de la página repite la
 * redirección aunque el script no cargue. Vive fuera del HTML para que
 * las pruebas de Node (`src/test/js/`) ejerciten el mismo comportamiento
 * que ve el usuario.
 */
(function () {
    "use strict";

    var destino = document.getElementById("ir-ahora").href;
    var cuenta = document.getElementById("cuenta");
    var restante = parseInt(cuenta.textContent, 10);
    var reloj = setInterval(function () {
        restante -= 1;
        cuenta.textContent = String(restante);
        if (restante <= 0) {
            clearInterval(reloj);
            window.location.href = destino;
        }
    }, 1000);

    // Sin API de portapapeles (origen no seguro) el botón ofrece el
    // prompt para copiar la dirección a mano.
    var copiar = document.getElementById("copiar");
    copiar.addEventListener("click", function () {
        var copia = window.navigator.clipboard
                ? window.navigator.clipboard.writeText(destino)
                : Promise.reject();
        copia.then(function () {
            copiar.textContent = "Copiada";
        }, function () {
            window.prompt("Copiá la nueva dirección:", destino);
        });
    });
}());
