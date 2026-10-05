# Issue tracker: GitHub

Issues y especificaciones viven en SpendIce/PP6-TPIntegrador.
Usar `gh` y especificar `--repo SpendIce/PP6-TPIntegrador` en operaciones de issues, PRs y etiquetas.

## Operaciones

- Crear: `gh issue create --repo SpendIce/PP6-TPIntegrador --title "..." --body-file <archivo>`.
- Leer: `gh issue view <n> --repo SpendIce/PP6-TPIntegrador --comments`; consultar también etiquetas.
- Listar: `gh issue list --repo SpendIce/PP6-TPIntegrador` con filtros de estado y etiquetas.
- Comentar: `gh issue comment <n> --repo SpendIce/PP6-TPIntegrador --body-file <archivo>`.
- Etiquetar: `gh issue edit <n> --repo SpendIce/PP6-TPIntegrador --add-label "..."`. Para retirar etiquetas, usar `--remove-label`.
- Cerrar: `gh issue close <n> --repo SpendIce/PP6-TPIntegrador`.

Para cuerpos multilínea, escribir el texto en un archivo y usar `--body-file`.

Publicar al tracker significa crear un issue.
Consultar un ticket significa leer su cuerpo, comentarios y etiquetas.

## Pull requests as a triage surface

PRs as a request surface: no.

## Wayfinding

El mapa es un issue con etiqueta `wayfinder:map`.
Los tickets se vinculan como sub-issues y usan `wayfinder:<tipo>`.
Si sub-issues no está disponible, usar una lista de tareas en el mapa y `Part of #<mapa>` en cada ticket.

Registrar bloqueos mediante dependencias nativas de GitHub.
Si no están disponibles, usar `Blocked by: #<n>`.
Un ticket está disponible cuando sus bloqueantes están cerrados y no tiene responsable asignado.

Reclamar asignando el ticket al desarrollador.
Al resolverlo, comentar el resultado, cerrar el ticket y agregar el enlace y la decisión al mapa.
