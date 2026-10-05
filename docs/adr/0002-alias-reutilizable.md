---
status: accepted
---

# Separar la identidad de la asignación del alias reutilizable

Se acuerda que cada solicitud crea una asignación independiente, vigente durante 60 minutos desde su creación. El alias público puede reutilizarse después del vencimiento y esa reutilización es un comportamiento objetivo de la primera etapa. Por eso, el alias no representa la identidad permanente de una asignación.

El cliente representado por el usuario acepta que un enlace o QR antiguo pueda conducir al destino de una asignación posterior del mismo alias. Se descarta deduplicar solicitudes por URL de destino, porque cada creación debe conservar una duración independiente.

Reutilizar alias permite limitar la creación de nuevos códigos públicos, pero por sí solo no limita el volumen de registros persistidos. En Q9 se eligió conservar las asignaciones anteriores, conforme al ADR 0003. Reasignar un alias no debe sobrescribir su historial.

En Q19 y Q20 se acordó reciclar primero los alias vencidos, priorizando los de menor longitud. Si no hay alias reutilizables, se generan códigos nunca usados: primero se agotan los de un carácter, luego los de dos y así sucesivamente. No se fija una longitud de cinco caracteres. Se usa un alfabeto de 58 símbolos, excluyendo `0`, `O`, `I` y `l`, con distinción de mayúsculas y minúsculas y exclusión de las rutas del sistema. Los empates de longitud quedan a criterio de implementación.

La persistencia debe conservar qué códigos ya se utilizaron y permitir reservar el siguiente sin duplicaciones entre solicitudes concurrentes. La política de selección se mantiene separada del mecanismo transaccional.

Acuerdos: respuestas Q1, Q2, Q9, Q19 y Q20 de la entrevista iniciada el 2026-10-05.
