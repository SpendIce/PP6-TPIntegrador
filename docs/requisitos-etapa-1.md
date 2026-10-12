# Acuerdos y pendientes de la etapa 1

Fuente: [consigna original](../TP_PP6_v1.0.md). Este documento complementa la consigna con las respuestas de la elucidación; no reemplaza el contrato OpenAPI ni los criterios de aceptación completos. La columna «Definido por» indica si el acuerdo lo confirmó el docente, si es una decisión del equipo o si todavía falta confirmar quién lo definió.

Estado: entrevista cerrada con confirmación explícita del usuario de que los documentos reflejan el alcance y las reglas acordadas. La documentación fue revisada y sus enlaces locales verificados. El siguiente paso es completar OpenAPI y la especificación técnica antes de implementar; los resultados de pruebas y del experimento de adaptación todavía no existen.

## Acuerdos de las rondas 1 a 4

| Tema | Acuerdo | Origen | Definido por |
| --- | --- | --- | --- |
| Vigencia | Exactamente 60 minutos desde la creación; al alcanzar el vencimiento deja de resolver. El tiempo apagado cuenta y los reinicios no renuevan la duración. | Consigna, Q1 y Q10 | Docente |
| Reutilización | Preferir alias vencidos a códigos nuevos y priorizar los vencidos de menor longitud. Se acepta que un enlace o QR viejo pueda llevar a un destino nuevo. | Q1 y Q19 | Docente |
| Generación | Si no hay vencidos reutilizables, generar códigos nunca usados, agotando un carácter antes de pasar a dos y así sucesivamente. Alfabeto de 58 símbolos sin `0`, `O`, `I`, `l`, con distinción de mayúsculas y exclusión de rutas del sistema. | Q20 | Docente |
| Solicitudes repetidas | Cada solicitud crea una asignación y una duración independientes, aunque repita la URL de destino. | Q2 | Docente |
| Validación | Comprobar formato HTTP/HTTPS sin consultar disponibilidad. Permitir destinos de red local y credenciales incrustadas. Rechazar enlaces del propio acortador y conservar parámetros y fragmentos. | Q3 y Q12 | Docente; el rechazo de enlaces propios, equipo (Q23) |
| Entrada de destino | Hasta 8.192 caracteres; rechazar formato inválido y espacios sin codificar, sin truncar ni corregir silenciosamente el destino. Los espacios deben representarse mediante codificación válida, por ejemplo `%20`. | Q25 | Equipo |
| Alcance funcional | Crear y resolver enlaces; sin cuentas ni consola de gestión en esta etapa. Una consola puede considerarse después. | Q4 | Docente |
| Extensiones | Chrome y Firefox; acortar la URL de la pestaña activa, mostrar resultado y QR, instalación manual. | Consigna y Q5 | Docente |
| Descarga de QR | Disponible en ambos clientes, web y extensión, como PNG. | Q5 y Q17 | Docente |
| Presentación del resultado | Mostrar enlace, QR, hora de vencimiento y aviso de posible reutilización posterior del alias en web y extensión. | Q17 | Sin confirmar |
| Demo | Servicio accesible por red local para usarlo desde un celular; también accesible desde el equipo anfitrión. | Q6 | Equipo |
| Equipo y plazo | Dos integrantes con poca experiencia, aunque no nula, en Java/Spring. Primera etapa en siete días, el lunes siguiente; duración total de un mes y medio. | Respuesta sobre equipo y plazo | Equipo |
| Arquitectura | Monolito modular con reglas, casos de uso y adaptadores separados. | Q8 y ADR 0001 | Equipo |
| Historial | Conservar identificador de asignación, alias, destino completo, creación y vencimiento, aunque se reutilice el alias. Sin purga automática durante el trabajo ni registro de visitas en esta etapa. | Q9, Q18 y ADR 0003 | Equipo |
| Alias sin asignación vigente | Página con el mensaje «Este enlace no existe o venció» y respuesta HTTP 404, tanto para vencidos como para desconocidos. | Q11 | Equipo |
| Capacidad de referencia | Verificar 10 clientes simultáneos y 1.000 asignaciones vigentes como objetivo inicial, sin compromiso de servicio público. No es un límite comercial para rechazar la creación número 1.001. | Q13 y Q19 | Equipo |
| Tiempo de respuesta | Al menos el 95 % de las solicitudes de creación y resolución deben responder en un segundo o menos, con el servicio iniciado en red local. Se excluye la carga del destino externo. | Q26 | Equipo |
| Base de datos | PostgreSQL para la aplicación y las pruebas de integración. | Q14 y ADR 0004 | Equipo |
| Herramientas | Java 17 y Maven; Spring Boot estable compatible, versión concreta pendiente de especificación técnica. | Q15 | Docente |
| Cliente web | HTML/CSS/JavaScript servido por el backend, consumiendo la API REST. | Q15 | Docente |
| Transporte para demo | El usuario no distingue HTTP de HTTPS si el acceso funciona y resulta transparente. Se elige HTTP para la demo LAN; no se añade TLS como requisito específico de esta entrega. | Q16 y elección técnica derivada | Docente |
| Reintentos de creación | Si se pierde una respuesta y se repite la solicitud, se acepta una segunda asignación. No se deduplica por destino ni se exige idempotencia de la operación. | Q21 | Docente |
| Instante de resolución | Evaluar vigencia al resolver en el servidor; una respuesta emitida a partir de una asignación todavía vigente puede llegar después del vencimiento. | Q22 | Sin confirmar |
| Enlaces propios | Rechazar destinos pertenecientes a los orígenes configurados del acortador, incluyendo sus direcciones equivalentes localhost y LAN. | Q23 | Equipo |
| Evidencia de QA | Pruebas de creación, vencimiento, reinicio, reutilización con historial, concurrencia, errores y contratos; demo manual de web, ambos complementos y QR desde celular. | Q24 | Equipo |
| Evidencia de adaptación | Experimento de cambio de duración solo para nuevas asignaciones, más análisis de alias personalizados y consola de gestión. | Q24 | Equipo |

La consigna obliga a Java, Spring Boot, JPA/Hibernate, base relacional, API REST y cliente web; además exige descubrimiento, modelos de dominio, arquitectura de capas, OpenAPI/Swagger y especificaciones previos al código, e incluye pruebas unitarias y de integración.

## Escenarios derivados de los acuerdos

Estos escenarios expresan las respuestas ya dadas; los mecanismos concretos y el contrato completo todavía deben especificarse.

- Dos creaciones de la misma URL deben tener asignaciones independientes.
- Visitar un enlace no establece una nueva creación ni una nueva duración.
- Cuando se reasigna un alias vencido, el enlace y el QR que lo contienen pueden resolver al nuevo destino.
- Una URL de formato válido no debe rechazarse únicamente porque su destino no esté disponible para el servidor.
- El QR debe contener una dirección que el celular pueda alcanzar durante la demo en red local.
- Al alcanzar el instante de vencimiento, una asignación ya no resuelve, incluso si el servidor estuvo apagado durante parte de su duración.
- Cuando un alias se reasigna, su asignación anterior permanece en el historial pero no determina el destino actual.
- Si no hay una asignación vigente para el alias, se responde con HTTP 404 y el mensaje acordado.
- Los destinos con credenciales incrustadas no se rechazan por esa característica.
- La web y las extensiones muestran el vencimiento y permiten descargar el QR como PNG.
- Un alias vencido de un carácter se elige antes que uno vencido de dos caracteres.
- Desde una base vacía se utilizan los 58 códigos de un carácter antes de generar el primero de dos, salvo códigos reservados del sistema.
- Si todos los alias usados están vigentes, se genera el siguiente código no usado de la longitud mínima disponible.
- Dos creaciones simultáneas no pueden apropiarse del mismo alias vigente ni sobrescribir una asignación anterior.
- Repetir una creación después de perder la respuesta puede producir dos asignaciones válidas independientes.
- Una resolución efectuada antes del vencimiento sigue siendo válida aunque su respuesta llegue al navegador después.
- La capacidad de referencia de 1.000 asignaciones no habilita rechazar la siguiente por ese único motivo.
- Una entrada que exceda 8.192 caracteres se rechaza; nunca se guarda un destino truncado.
- Una entrada con espacios sin codificar se rechaza con un error visible; no se modifica silenciosamente para aceptarla.
- La prueba de capacidad debe verificar el objetivo temporal acordado además de las invariantes del servicio.

## Especificación técnica siguiente

Las siguientes tareas no cambian los acuerdos de producto: completar OpenAPI y casos de entrada inválida; precisar reservas y desempates de alias; elegir mecanismo transaccional; configurar la URL pública y los orígenes equivalentes; fijar versiones; preparar PostgreSQL y migraciones; definir el empaquetado manual de extensiones; y completar el procedimiento reproducible de prueba de capacidad.

El contrato debe expresar el límite de 8.192 caracteres y el rechazo de espacios sin codificar, y completar los casos de caracteres internacionales y formato inválido antes de implementar la validación. El objetivo temporal es que al menos el 95 % de las solicitudes respondan en un segundo o menos; no es un máximo absoluto para todas las solicitudes. No se fijó un porcentaje de cobertura. No se recibió una rúbrica adicional; los criterios acordados no equivalen a confirmar que no exista. Las fechas se conservan en términos relativos según lo informado por el usuario.

## Evaluación de flexibilidad acordada

Realizar un experimento de cambio de duración para nuevas asignaciones, conservando los vencimientos de las ya creadas, sin cambiar la duración de 60 minutos de la entrega. Analizar alias personalizados y consola de gestión sin implementarlos en esta etapa. Para cada escenario señalar qué módulo cambiaría, si modifica datos o contratos y qué pruebas conservarían el comportamiento previo. Agregar estadísticas sigue siendo un escenario útil para revisar dependencias, pero no una funcionalidad exigida.

El documento de [arquitectura y validación](arquitectura-y-validacion.md) concreta el diseño confirmado. El experimento y las pruebas todavía no se ejecutaron: en esta sesión solo se revisa y documenta el diseño.

## Documentos relacionados

- [Glosario](../CONTEXT.md).
- [ADR 0001: monolito modular](adr/0001-monolito-modular.md).
- [ADR 0002: alias reutilizable](adr/0002-alias-reutilizable.md).
- [ADR 0003: conservar historial](adr/0003-conservar-historial-asignaciones.md).
- [ADR 0004: PostgreSQL en integración](adr/0004-postgresql-en-integracion.md).
- [Arquitectura y validación](arquitectura-y-validacion.md).
