---
status: accepted
---

# Gradle con wrapper y perfil HSQLDB a pedido del docente

El 2026-10-07 el docente pidió convertir el build de Maven a Gradle e incluir en el repositorio la carpeta `gradle/` y los archivos `gradlew`, `gradlew.bat` y `gradle.properties`, para poder ejecutar el proyecto sin intervenir él. También pidió, si era posible, que funcionara con HSQLDB para no tener que montar Docker, con el datasource `jdbc:hsqldb:hsql://localhost:9001/xdb`, usuario `sa` y contraseña vacía. Como alternativa a Gradle ofreció subir el wrapper de Maven (`mvnw`).

Se eligió Gradle porque fue la opción que el docente pidió en primer lugar. Esta decisión reemplaza la mención de Maven del ADR 0001 y del acuerdo Q15; Java 17 no cambia.

HSQLDB se agrega como perfil `hsqldb` para ejecutar el servicio sin Docker y no reemplaza a PostgreSQL. Sin el perfil, la aplicación usa PostgreSQL y las pruebas de integración siguen corriendo contra PostgreSQL con Testcontainers, por lo que el ADR 0004 sigue vigente.

## Consecuencias

- El perfil `hsqldb` no tiene pruebas automáticas. La creación, la redirección y las respuestas de error se comprobaron manualmente al incorporarlo.
- En el perfil no corre Flyway: el esquema se crea con `db/hsqldb/schema.sql`, una traducción manual de las migraciones V1 y V2. Un cambio de esquema futuro debe aplicarse en los dos lugares.
- Al incorporarlo se atribuyó la falta de Flyway a que Flyway 11 no soporta HSQLDB. No es exacto: desde Flyway 10 cada motor se agrega como un módulo aparte (el build ya usa `flyway-database-postgresql`), y `flyway-database-hsqldb` está publicado en la versión 11.7.2 que administra Spring Boot 3.5.16. No se probó. Si funciona, permitiría eliminar el esquema duplicado.

Pedido: mensaje del docente del 2026-10-07; implementado en el PR #14.
