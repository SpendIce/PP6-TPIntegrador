---
status: accepted
---

# Monolito modular con reglas independientes de los adaptadores

El equipo tiene dos integrantes, experiencia limitada con Java/Spring, siete días para la primera etapa y un mes y medio para el trabajo completo. Se acuerda un monolito modular que separa reglas del negocio, casos de uso y adaptadores HTTP y JPA, manteniendo el stack obligatorio de la consigna. La separación permite cambiar reglas de vencimiento, generación de alias y validación sin extender dependencias del framework a todo el sistema.

Se prefirió esta estructura frente a capas que mezclen reglas con controladores y persistencia: requiere más disciplina inicial, pero facilita probar y modificar comportamientos. Las interfaces se introducen en puntos de variabilidad reales; no se anticipan funciones de etapas desconocidas ni se impone una interfaz por cada clase.

Los contratos de la API se describirán mediante DTO propios, evitando que las entidades de persistencia determinen el contrato de la web y las extensiones. En la segunda ronda se acordaron Java 17, Maven, PostgreSQL y una web con HTML/CSS/JavaScript servida por el backend que consume la API. La estructura concreta de paquetes, la versión estable compatible de Spring Boot y las bibliotecas siguen pendientes de especificación técnica.

Acuerdos: respuestas Q8, Q14 y Q15 de la entrevista iniciada el 2026-10-05.
