# Verificación del QR del enlace (issue #7)

Cómo se verifica que el QR mostrado y descargado en la web codifica
exactamente el enlace acortado devuelto por la API, según los criterios
del issue y del requisito «Clientes y QR» de la especificación padre.

## Verificación automática

### Prueba de contenido (Node)

```bash
cd src/test/js
node --test
```

`qr-code.test.mjs` ejercita el módulo real que usa la web
(`src/main/resources/static/qr-code.js`, `QrPng`) junto con la
biblioteca QR vendorizada (`vendor/qrcode.js`) y comprueba:

- que los bytes PNG generados se decodifican con `UPNG.js` (PNG →
  píxeles) y `jsQR` (píxeles → texto), recuperando **exactamente** el
  `shortUrl` de entrada — el mismo texto que la API devolvió, no la URL
  de destino;
- que dos enlaces distintos producen códigos con su propio contenido;
- la estructura del archivo: firma PNG, imagen cuadrada del tamaño
  esperado, zona de silencio clara y reproducción fiel de la matriz
  módulo a módulo;
- el nombre de descarga `qr-<alias>.png`.

La decodificación usa bibliotecas independientes del encoder bajo
prueba, por lo que un error en la generación del PNG haría fallar el
test: es la misma operación que hace el lector QR de un celular.

### Wiring HTTP (Maven)

```bash
mvn test
```

`LinkApiHttpTest#laWebReferenciaYSirveLosRecursosDelQr` levanta el
servicio completo y comprueba que `GET /` referencia
`vendor/qrcode.js`, `qr-code.js` y los elementos `qr-image` /
`qr-download`, y que ambos recursos se sirven con 200 sobre HTTP.

## Verificación visual y manual

La parte visual no se automatiza; es evidencia requerida de la demo:

1. Levantar PostgreSQL y el servicio con la dirección pública LAN:
   ```bash
   docker compose up -d
   PUBLIC_BASE_URL=http://<ip-lan>:8080 mvn spring-boot:run
   ```
2. Abrir `http://localhost:8080` (o la IP LAN desde otro equipo) e
   ingresar una URL de destino válida.
3. Comprobar que se muestran juntos: el enlace acortado, la hora de
   vencimiento, el aviso de posible reasignación del alias y el QR.
4. Escanear el QR en pantalla con un celular de la misma red: debe
   abrir el **enlace acortado** (la dirección LAN + alias), que
   redirige al destino mientras la asignación esté vigente.
5. Pulsar «Descargar QR como PNG»: el archivo `qr-<alias>.png` debe
   abrirse como imagen válida y escanearse con el mismo resultado.
6. Provocar un error de creación (p. ej. una URL mal formada) y
   comprobar que no aparece ningún QR ni resultado nuevo.
7. Repetir una creación exitosa: el QR mostrado corresponde al nuevo
   enlace, no al anterior.

## Estado

- Automático: pruebas de Node y de wiring implementadas y en verde.
- Manual/visual: procedimiento documentado; la evidencia de la demo en
  LAN (celular incluido) queda pendiente de la instancia de demostración
  como parte del recorrido completo web/complementos.
