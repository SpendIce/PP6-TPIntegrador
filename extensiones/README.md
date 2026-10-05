# Complementos del acortador — Chrome y Firefox

Entrega del issue #8: un complemento para Chrome y otro para Firefox que, con
una acción (clic sobre el ícono), toman la URL de la pestaña activa, la envían
al mismo `POST /api/links` que usa la web y muestran el enlace acortado, su
vencimiento, el aviso de reutilización y un QR descargable como PNG.

No se publican en tiendas: la instalación es manual y la dirección de la API
es configurable para la demo en red local.

## Estructura

```text
extensiones/
├── compartido/            Fuente de verdad del código y la UI.
│   ├── api.js             Cliente del contrato POST /api/links (UMD, testeable).
│   ├── png.js             Codificador PNG propio, sin canvas ni dependencias.
│   ├── qr.js              QR → PNG usando qrcodegen + png.js (UMD, testeable).
│   ├── popup.html/.css/.js UI del popup y pegado con las APIs del navegador.
│   └── vendor/
│       ├── qrcodegen.js   Librería QR de Nayuki v1.5.0 (MIT, ver LICENCIA.txt).
│       ├── qrcodegen.cjs  Puente CommonJS para usar el vendor en los tests.
│       └── LICENCIA.txt
├── chrome/                Paquete listo para cargar (Manifest V3).
├── firefox/               Paquete listo para cargar (Manifest V2).
├── tests/                 Pruebas con `node --test`, sin dependencias npm.
├── sincronizar.sh         Copia compartido/ → chrome/ y firefox/.
└── package.json           Solo declara el script de test; no hay dependencias.
```

`compartido/` es la fuente; `chrome/` y `firefox/` se generan con
`./sincronizar.sh` y solo agregan su `manifest.json`. Los tests verifican
que las copias sean idénticas a la fuente.

## Instalación manual

### Chrome (Manifest V3)

1. Abrí `chrome://extensions`.
2. Activá el **Modo de desarrollador** (interruptor arriba a la derecha).
3. Clic en **Cargar extensión sin empaquetar** (Load unpacked) y elegí el
   directorio `extensiones/chrome/`.
4. Debe aparecer la tarjeta «Acortador de enlaces (demo LAN)». Opcional:
   fijá el ícono con la chincheta del menú de extensiones.
5. Verificación rápida: con el servicio levantado, abrí cualquier página
   `http://` o `https://` y hacé clic en el ícono del complemento.

### Firefox (Manifest V2)

1. Abrí `about:debugging`.
2. En el menú lateral elegí **Este Firefox** (This Firefox).
3. Clic en **Cargar complemento temporal** (Load Temporary Add-on) y elegí
   el archivo `extensiones/firefox/manifest.json`.
4. El complemento aparece en la barra de herramientas; el recorrido de uso
   es el mismo que en Chrome.

> **Consecuencia honesta:** la instalación manual en Firefox es temporal y el
> complemento se desinstala al reiniciar el navegador. Para una instalación
> permanente haría falta firmar el complemento (AMO) o usar una edición que
> admita desactivar la firma (Developer Edition/Nightly con
> `xpinstall.signatures.required=false`), ambas fuera del alcance acordado:
> la demo usa carga temporal.

#### ¿Por qué MV2 en Firefox y MV3 en Chrome?

Los dos manifiestos son lo único que cambia entre paquetes. En Firefox MV3,
los `host_permissions` se tratan como permisos de **otorgamiento en tiempo
de ejecución**: el navegador puede dejar la solicitud a la API bloqueada
hasta que el usuario la habilite, un fallo silencioso justo en el camino de
la demo. En MV2 los patrones de host dentro de `permissions` se otorgan al
instalar, que es el comportamiento necesario para una instalación manual
confiable. Mozilla mantiene el soporte de MV2 para carga manual; si en una
etapa posterior se retira, migrar el manifiesto a MV3 no toca el resto del
código (el popup ya usa la API de promesas compartida `browser`/`chrome`).

## Configuración de la dirección de la API

- En el popup, sección **Dirección de la API**: escribir el origen del
  servicio y **Guardar**. Se persiste en `storage.local` del navegador.
- Valor por defecto: `http://localhost:8080` (dejar el campo vacío lo
  restaura).
- Se acepta `192.168.1.50:8080` sin esquema (se asume `http://`). Se
  rechazan esquemas que no sean `http`/`https` y direcciones con
  credenciales.
- Para la demo con celular: levantar el servicio con la IP de red local
  (`PUBLIC_BASE_URL=http://192.168.x.y:8080`, ver README raíz) y configurar
  esa misma dirección en cada complemento. El `shortUrl` y el QR siempre
  salen de la respuesta de la API, no de esta configuración.

## Uso

1. En una pestaña `http(s)://` cualquiera, clic en el ícono del complemento.
2. El popup solicita la creación inmediatamente (esa es la acción única) y
   muestra: destino, enlace acortado clicable, hora local de vencimiento,
   aviso de reutilización y el QR.
3. **Descargar QR como PNG** guarda `qr-<alias>.png` en Descargas. El QR
   codifica el `shortUrl` de la respuesta.
4. **Acortar de nuevo** / **Reintentar** repiten la solicitud. Coherente con
   el contrato, cada reintento puede crear una asignación nueva; el popup no
   promete idempotencia ni conserva el resultado anterior al cerrarse.

### Errores visibles

Un fallo nunca se muestra como éxito. La sección de error muestra el código
y el mensaje:

- `NETWORK_ERROR`: no se pudo conectar con la API configurada.
- `REQUEST_TIMEOUT`: la API no respondió en 10 segundos.
- `TAB_NOT_SHORTENABLE`: la pestaña activa no es `http(s)://`
  (`chrome://`, `about:`, `file://`, etc.); no se llama a la API.
- `CONFIG_INVALID`: la dirección configurada no es válida.
- `INVALID_RESPONSE`: la API respondió algo fuera del contrato.
- `HTTP_<estado>`: respuesta inesperada sin cuerpo de error.
- Los rechazos `400` muestran `error` y `message` del contrato
  (`MALFORMED_DESTINATION`, `OWN_ORIGIN`, etc.).

## Decisiones técnicas

- **Sin cambios en el backend (CORS).** Con permiso de host, las páginas de
  la extensión (popup) hacen `fetch` cross-origin sin exigir CORS al
  servidor; se verificó que el backend no tiene configuración CORS porque no
  la necesita para estos clientes. Si se quitaran los permisos de host haría
  falta agregar CORS en el backend, y ni siquiera eso alcanzaría de forma
  limpia: el origen `moz-extension://<uuid>` es aleatorio por instalación.
- **Permisos mínimos justificados:** `activeTab` para leer la URL de la
  pestaña al invocar la acción (no `tabs`), `storage` para persistir la
  configuración, `downloads` para guardar el PNG, y host `http(s)://*/*`
  porque la dirección de la API la define el usuario y no es enumerable.
- **QR sin red ni canvas:** `qrcodegen.js` (Nayuki v1.5.0, MIT) vendorizado
  genera la matriz y `png.js` codifica el PNG directamente a bytes (zlib de
  bloques almacenados + CRC32 propios). La demo es LAN: no hay CDN ni
  dependencia de la web. El mismo data URL alimenta el `<img>` del popup y
  `downloads.download`.
- **Módulos UMD sin build:** `api.js`, `png.js` y `qr.js` se cargan con
  `<script>` en el popup (global `LinkApi`/`PngEncoder`/`QrImage`) y con
  `require` en los tests. `popup.js` usa `browser`/`chrome` con promesas,
  API común a ambos navegadores.
- **Sin íconos propios:** los paquetes usan el ícono genérico del navegador;
  no afecta el recorrido de la demo.

## Pruebas automatizadas

```bash
cd extensiones
node --test          # también: npm test
```

45 pruebas, cero dependencias (Node >= 20, verificado con Node 26):

- `tests/api.test.js`: normalización de la dirección de API, construcción
  del POST del contrato, parseo de 201/400/estados inesperados, errores de
  red y timeout con `fetch` inyectado, formato de vencimiento.
- `tests/contrato.test.js`: integración con un servidor `node:http` que
  responde como la API y `fetch` real: valida método, ruta, headers y body.
- `tests/png.test.js`: firma PNG, estructura de chunks, CRC32/Adler-32
  contra `node:zlib`, inflate de IDAT y píxeles esperados.
- `tests/qr.test.js`: matriz QR, dimensiones y zona de silencio del PNG,
  determinismo y data URL. El PNG generado fue decodificado con `zbarimg`
  durante el desarrollo y reproduce el `shortUrl` exacto.
- `tests/manifiestos.test.js` y `tests/sincronizacion.test.js`: permisos y
  archivos referenciados de cada paquete, e igualdad byte a byte entre
  `compartido/` y los directorios de cada navegador.

`popup.js` (pegado con `tabs`/`storage`/`downloads`) no es testeable en
node: su verificación es el procedimiento manual siguiente.

## Verificación manual (procedimiento, ambos navegadores)

1. Levantar el servicio: `docker compose up -d` y `mvn spring-boot:run`.
2. Instalar el complemento según las instrucciones de arriba.
3. Configurar la dirección de la API si el servicio no está en el default.
4. En una pestaña `https://` abierta, clic en el ícono: deben verse el
   enlace corto, el vencimiento, el aviso de reutilización y el QR.
5. Visitar el `shortUrl` en otra pestaña: debe redirigir (HTTP 302) al
   destino original.
6. Clic en **Descargar QR como PNG**: abrir el archivo de Descargas y
   decodificarlo con un celular o `zbarimg`: contiene el `shortUrl`.
7. Errores esperados: sobre `chrome://`/`about:` aparece
   `TAB_NOT_SHORTENABLE`; con el servicio apagado, `NETWORK_ERROR`;
   intentando acortar el propio servicio, `OWN_ORIGIN` desde la API.
8. Repetir la acción crea otra asignación independiente (comportamiento del
   contrato, no un defecto del cliente).

## Limitaciones conocidas

- La instalación en Firefox es temporal (se pierde al reiniciar el
  navegador); la persistencia de la configuración dura lo que dure la sesión
  del complemento temporal.
- Abrir el popup siempre solicita una creación: es la «acción única» de la
  especificación; no hay resultado persistente entre aperturas.
- Los permisos de host son amplios (`http(s)://*/*`) a propósito, porque la
  API es configurable; no hay acceso a contenido de páginas más allá de la
  lectura de la URL de la pestaña invocada.
