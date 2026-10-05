# Estándares de código

Reglas para el review. Cada una nació de un hallazgo real en la etapa 1. Si una
regla puede volverse chequeo automático, el chequeo la reemplaza y esta entrada
se borra.

## Reuso antes que duplicación

- **Una sola implementación por capacidad.** Antes de escribir o vendorizar
  algo, buscar si ya existe: `src/main/resources/static/vendor/`,
  `extensiones/`, `src/test/js/vendor/`. El stack QR→PNG es
  `static/qr-code.js` + `static/vendor/qrcode.js`; cualquier cliente nuevo lo
  reusa (las extensiones lo copian vía `sincronizar.sh`). No se acepta un
  segundo vendor QR ni un segundo encoder PNG sin ADR.
- **Suites HTTP de integración extienden `HttpApiFixture`.** No se copia el
  bloque `@SpringBootTest`/contenedor/`HttpClient`/`assertError` a una suite
  nueva: el fixture es el único lugar donde vive esa infraestructura.

## Vocabulario

- Nombres de clases, tests y docs usan los términos de `CONTEXT.md` (asignación,
  alias, enlace acortado, vencimiento, reasignación). Un test que dice "link
  expirado" donde el glosario dice «vencida» es un hallazgo de review.

## Documentación viva

- La evidencia de cada ticket va en su propio archivo (`docs/evidencia-*.md`,
  `docs/verificacion-*.md`). `docs/especificacion-tecnica.md` solo acumula la
  sección de «Estado» y las decisiones transversales: es un hotspot de merge,
  no un diario de ejecución.
- Un refactor que deja falsa una afirmación de un doc es un defecto del
  refactor, no un pendiente del doc.

## Pruebas

- Toda clase `*HttpTest` corre sobre PostgreSQL real (Testcontainers), nunca
  H2 ni mocks de repositorio (ADR-0004).
- Un comportamiento del contrato que la spec exige tiene prueba automatizada;
  "queda verificado a mano" solo vale para pasos físicamente humanos
  (instalación de complementos, celular, verificación visual), que se declaran
  explícitamente como pendientes.
