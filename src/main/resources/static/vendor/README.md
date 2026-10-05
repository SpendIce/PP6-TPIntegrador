# Dependencias vendorizadas de la web

La demo corre en red local sin garantía de acceso a Internet, por lo que
no se usan CDN: las bibliotecas del cliente se sirven desde el propio
backend junto con la web.

| Archivo | Biblioteca | Versión | Origen | Licencia |
| --- | --- | --- | --- | --- |
| `qrcode.js` | qrcode-generator (Kazuhiko Arase) | 1.4.4 | https://github.com/kazuhikoarase/qrcode-generator / `npm i qrcode-generator` | MIT (`qrcode-generator.LICENSE.txt`) |

`qrcode.js` expone el global `qrcode` (UMD: también `module.exports`
bajo CommonJS, lo que permite importarlo desde las pruebas de Node).
Genera la matriz del código QR; la conversión a imagen PNG es propia
(`../qr-code.js`), sin dependencias adicionales.

Para actualizar: reemplazar el archivo por la misma entrada del paquete
`qrcode-generator` de npm (`qrcode.js` en la raíz del paquete) y
actualizar la versión en esta tabla.
