# Issue tracker: GitHub

Issues y especificaciones viven en SpendIce/PP6-TPIntegrador.
Usar `gh` y especificar `--repo SpendIce/PP6-TPIntegrador` en operaciones de issues, PRs y etiquetas.

## Operaciones

- Crear: `gh issue create --repo SpendIce/PP6-TPIntegrador --title "..." --body-file <archivo>`.
- Leer: `gh issue view <n> --repo SpendIce/PP6-TPIntegrador`; `--comments` solo si el hilo hace falta (los cuerpos largos inflan la salida).
- Listar: `gh issue list --repo SpendIce/PP6-TPIntegrador` con filtros de estado y etiquetas. Para barridos, salida compacta: `gh issue list --repo SpendIce/PP6-TPIntegrador --json number,title,labels,state` (sin `body`/`comments`).
- Comentar: `gh issue comment <n> --repo SpendIce/PP6-TPIntegrador --body-file <archivo>`.
- Etiquetar: `gh issue edit <n> --repo SpendIce/PP6-TPIntegrador --add-label "..."`. Para retirar etiquetas, usar `--remove-label`.
- Cerrar: `gh issue close <n> --repo SpendIce/PP6-TPIntegrador`.

Para cuerpos multilínea, escribir el texto en un archivo y usar `--body-file`.

Publicar al tracker significa crear un issue.
Consultar un ticket significa leer su cuerpo, comentarios y etiquetas.

## Pull requests as a triage surface

PRs as a request surface: no.

## Wayfinding

Registrar bloqueos mediante dependencias nativas de GitHub.
Si no están disponibles, usar `Blocked by: #<n>`.

Si existe un issue mapa con etiqueta `wayfinder:map`, los tickets se vinculan
como sub-issues o con `Part of #<mapa>`; si no existe, no crearlo sin pedido
del usuario.

Un ticket está disponible cuando sus bloqueantes están cerrados.
Una rama `issue/<n>-*` o un comentario de trabajo en curso ya lo reclama; no
hace falta asignar responsables en este proyecto.

Al resolverlo, comentar el resultado y cerrar el ticket. Los pasos que requieren
verificación humana física (instalación de complementos, celular, visual) no
cierran un ticket `ready-for-agent`: van en uno propio con `ready-for-human`.
