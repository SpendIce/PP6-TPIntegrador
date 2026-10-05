# Dependencias vendorizadas de las pruebas JS

Bibliotecas usadas solo por las pruebas de Node (`src/test/js/`); no se
sirven al navegador ni forman parte de la aplicación.

| Archivo | Biblioteca | Versión | Origen | Licencia |
| --- | --- | --- | --- | --- |
| `jsQR.js` | jsQR (Cozmo) | 1.4.0 | https://github.com/cozmo/jsQR / `npm i jsqr` (`dist/jsQR.js`) | Apache-2.0 (`jsQR.LICENSE.txt`) |
| `UPNG.js` | UPNG.js (Photopea) | 2.1.0 | https://github.com/photopea/UPNG.js / `npm i upng-js` | MIT (`UPNG.LICENSE.txt`) |

Roles en la verificación del QR (`qr-code.test.mjs`):

- `UPNG.js` decodifica los bytes PNG producidos por `qr-code.js` a
  píxeles RGBA. En Node declara `require("pako")` para desinflar; las
  pruebas lo cargan con un shim mínimo sobre `node:zlib`
  (`png-decode.mjs`), sin modificar el archivo vendorizado.
- `jsQR.js` decodifica el contenido del código QR a partir de los
  píxeles RGBA, comprobando que el PNG codifica exactamente el enlace
  acortado.
