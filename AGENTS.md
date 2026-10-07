## Agent skills

### Issue tracker

Para trabajar con issues, leer `docs/agents/issue-tracker.md`.
Tracker: GitHub Issues de SpendIce/PP6-TPIntegrador.

### Triage labels

Antes de clasificar issues, leer `docs/agents/triage-labels.md`.

### Domain docs

Antes de explorar o diseñar, leer `docs/agents/domain.md`.
Layout: single-context, con CONTEXT.md y docs/adr/.

### Review

Antes de revisar un diff, leer `CODING_STANDARDS.md`.

### Verificación

- Tests: `./gradlew test`; `node --test` dentro de `src/test/js/` y `extensiones/`.
- Resumen sin re-correr la suite: `build/test-results/test/*.xml` (los tests `@Nested` solo cuentan en el XML) o el reporte `build/reports/tests/test/index.html`.
- Nunca `./gradlew build` ni `./gradlew check` como verificación de cambios salvo pedido explícito.
- Los scripts de `scripts/` dejan PostgreSQL efímero en puertos 5543x; el `coco-postgres` en 5432 es de otro proyecto y no se toca.
