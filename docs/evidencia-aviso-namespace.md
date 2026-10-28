# Evidencia del aviso en el namespace anterior durante la gracia (issue #25)

Verificación del Change `issue-25-aviso-namespace-anterior`, contra los
criterios de su spec. Las decisiones están en el
[diseño del cambio](../.atl/changes/issue-25-aviso-namespace-anterior/design.md);
el contrato en `openapi.yaml` y la sección de resolución de
[especificacion-tecnica.md](especificacion-tecnica.md). El aviso se apoya
en el cambio de namespace con gracia del issue #24, cuya evidencia está
en [evidencia-namespace.md](evidencia-namespace.md).

## Qué se implementó

- `GET /{namespace}/{aliasPersonalizado}`: la resolución busca primero
  entre los namespaces actuales (`resolverEnlacePersonal`, `302` con
  `Location` al destino y `Cache-Control: no-store`) y, si el nombre no
  es actual, entre los anteriores en gracia
  (`avisoDeEnlacePersonalAnterior`). Con el nombre anterior en gracia y
  el enlace personal vigente responde `200` con `Cache-Control:
  no-store` y la página de aviso: nueva dirección completa armada con
  `shortener.public-base-url` (no con el pedido), hora límite —la que
  ocurra primero entre el fin de la gracia de ese nombre y el
  vencimiento del enlace—, demora de la redirección automática, cuenta
  regresiva, botón para copiar la dirección y el enlace «Ir ahora» que
  conduce al destino sin JavaScript; un `meta refresh` repite la
  redirección tras la demora. En los demás casos —namespace
  inexistente, gracia terminada, enlace inexistente o vencido— responde
  `404` con la página acordada y `no-store`.
- El aviso se compone en cada resolución desde `namespace_anterior`
  (nombre y `grace_ends_at`), `usuario.namespace` (dirección nueva) y
  `asignacion_personal` (vigencia y vencimiento), sin tabla ni estado
  propio; el record `AvisoNamespaceAnterior` transporta los datos al
  controlador. Cualquier enlace personal vigente del usuario responde
  con el aviso bajo cualquiera de sus anteriores en gracia, incluidos
  los creados después del cambio, y con cambios encadenados todos los
  anteriores muestran el namespace actual. Un nombre recuperado por su
  dueño vuelve a ser actual y redirige directamente; al terminar la
  gracia el nombre queda libre y, si otro usuario lo toma y crea el
  mismo alias personalizado, la dirección conduce a su destino con `302`.
- Configuración `cuenta.namespace-notice-delay`
  (`NAMESPACE_NOTICE_DELAY`, default `PT5S`): demora de la redirección
  automática del aviso, validada como duración positiva por
  `CuentaProperties` y leída en cada resolución.

## Pruebas

- `AvisoNamespaceAnteriorHttpTest` (HTTP contra PostgreSQL real,
  `MutableClock`): el namespace anterior en gracia sirve el aviso `200`
  con `no-store`, dirección completa, hora límite (vencimiento del
  enlace cuando ocurre primero), demora `PT5S` por defecto e «Ir
  ahora»; un enlace creado después del cambio responde con el aviso y
  el límite del fin de la gracia; los cambios encadenados `juan →
  juani → juanito` muestran `juanito` desde ambos anteriores; el `404`
  con la página acordada cubre alias inexistente, enlace vencido con
  gracia vigente y gracia terminada; terminada la gracia el nombre
  tomado por otro usuario conduce a su destino con `302`; el nombre
  recuperado por su dueño redirige directamente (precedencia del
  namespace actual); y «Ir ahora» se puede seguir sin JavaScript hasta
  el destino.
- `AvisoNamespaceDemoraConfiguradaHttpTest`: con otro
  `cuenta.namespace-notice-delay` (`PT12S`) la página lo refleja en el
  `meta refresh`, el atributo `data-demora` y la cuenta regresiva.
- `CuentaPropertiesTest`: obligatoriedad y positividad de
  `namespace-notice-delay` junto a las demás duraciones de `cuenta`.

## Ejecutado

Corrida del 2026-10-28:

```text
./gradlew --no-daemon --console=plain test
BUILD SUCCESSFUL   (163 pruebas, 0 fallos, 27 suites)

cd src/test/js && node --test    -> pass 35
cd extensiones && node --test    -> pass 42
```

Cubre las suites HTTP de las etapas anteriores y las nuevas del aviso,
contra PostgreSQL real. No se desactivó ni adaptó ninguna prueba al
entorno; `web-stub.mjs` sumó `replaceChildren` y `querySelectorAll` al
elemento falso, que `app.js` usa desde el issue #24.
