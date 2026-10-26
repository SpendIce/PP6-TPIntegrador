---
status: accepted
---

# Segundo service para usuarios, namespaces y vinculación

El [ADR 0006](0006-service-unico-hql-mappings.md) adoptó, a pedido del docente, un único `@Service` con los casos de uso como métodos públicos. La etapa 2 suma un área distinta del acortamiento: usuarios, namespaces, períodos de gracia, vinculación por QR y cierre de sesiones vinculadas. Se acuerda agregar un segundo service, `CuentaService`, con el mismo estilo que el primero: `EntityManager` con HQL directo, entities en `mappings`, reglas como helpers privados e inyección con `@Autowired` en los campos. `AcortadorService` solo suma la creación y la resolución de enlaces personales, y la configuración de Spring Security vive en `config`.

Se prefirió frente a extender `AcortadorService` con toda el área de cuentas porque ese archivo pasaría a mezclar dos responsabilidades independientes y dejaría de leerse de un vistazo, que fue justamente la motivación del ADR 0006. Esta decisión extiende el 0006 sin revertirlo: no vuelven los puertos, los stores ni los repositorios, y la verificación sigue concentrada en la API HTTP sobre PostgreSQL real.

Acuerdo: respuesta Q15 de la entrevista de la etapa 2 iniciada el 2026-10-26.
