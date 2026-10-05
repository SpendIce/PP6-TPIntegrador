---
status: accepted
---

# Usar PostgreSQL en la aplicación y en las pruebas de integración

Se acuerda PostgreSQL como motor relacional desde la primera etapa, accedido mediante JPA/Hibernate, y usar el mismo motor para verificar la persistencia en integración. Se prefirió frente a iniciar con H2 y migrar luego: requiere preparar su ejecución, pero permite verificar el comportamiento sobre el motor elegido sin depender de compatibilidad entre bases.

La forma de ejecución y el mecanismo de preparación de la base para las pruebas todavía deben especificarse. Este acuerdo no modifica la separación entre reglas del dominio y persistencia ni exige un servicio de base de datos accesible públicamente.

Acuerdo: respuesta Q14 de la entrevista iniciada el 2026-10-05.
