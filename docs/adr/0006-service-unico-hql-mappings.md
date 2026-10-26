---
status: accepted
---

# Service único con HQL sobre mappings, a pedido del docente

El docente mostró en clase un esquema de paquetes distinto del adoptado en el [ADR 0001](0001-monolito-modular.md) y pidió aplicarlo: un único `@Service` (`AcortadorService`) con los casos de uso como métodos públicos (`acortar`, `resolver`) y las reglas de negocio como helpers privados del mismo archivo; entities públicas en el paquete `mappings` (`Alias`, `Asignacion`, `GeneradorAlias`) que pasan a ser el modelo del sistema; acceso a datos con `EntityManager` y HQL inline, sin repositorios Spring Data ni stores; controllers pass-through con un método del service por endpoint; e inyección con `@Autowired` en los campos, fiel al ejemplo de clase. El 2026-10-23 se acuerda adoptarlo (issue #15) y el paquete raíz se reorganiza en `controller`, `service`, `mappings` y `config`.

La motivación es pedagógica: que el comportamiento completo del sistema se pueda leer navegando pocos archivos, sin perseguir definiciones cruzadas entre controllers, casos de uso, puertos, stores y repositorios, y que la estructura sea reconocible como la mostrada en clase. Esta decisión revisa la separación hexagonal del ADR 0001; del mismo acuerdo se conservan el monolito, el stack y los contratos de la API.

## Consecuencias

- El sacrificio es deliberado: se pierde la separación hexagonal y con ella las pruebas unitarias rápidas de las reglas, que ahora viven inline en el service. Esa cobertura migra al seam único de verificación, la API HTTP sobre PostgreSQL real (Testcontainers), que ya ejercitan las suites `*HttpTest`. Los tests unitarios de casos de uso y de dominio se retiran; lo que solo ellos cubrían se porta a pruebas HTTP antes de borrarlos.
- Los ADR 0002 y 0003 siguen vigentes: la reserva del alias, la inserción de la asignación y la actualización de la referencia actual siguen siendo una unidad atómica (`@Transactional` en `acortar`, con la fila única de `generador_alias` bloqueada por `em.find` con `PESSIMISTIC_WRITE`), el historial de asignaciones se conserva al reasignar y `resolver` corre en `@Transactional(readOnly = true)`.
- El contrato HTTP, los clientes (web, extensiones, QR) y el esquema de base de datos no cambian: las tablas y columnas son las mismas y `ddl-auto=validate` sigue pasando.
- Como parte del mismo pedido (nada hardcodeado), todo valor variable queda declarado en el bloque `shortener:` de la configuración, cada clave con su variable de entorno: `public-base-url`, `link-duration`, `own-origins`, `reserved-routes`, `alias-alphabet`, `max-destination-length` y `alias-route-pattern` (ver «Configuración de despliegue» en la [especificación técnica](../especificacion-tecnica.md)).

Pedido: esquema de clase mostrado por el docente e issue #15 (2026-10-23).

El 2026-10-26, en la etapa 2, se agregó un segundo service para usuarios, namespaces y vinculación: ver el [ADR 0008](0008-segundo-service-cuentas.md).
