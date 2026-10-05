
# Trabajo Práctico Integrador: Desarrollo de Software Guiado por IA y Calidad Arquitectónica

**Cátedra:** Paradigmas de Programación VI

**Nivel:** 5to Año – Ingeniería en Informática

**Modalidad:** Grupal

**Stack Tecnológico Obligatorio:** Java, Spring Boot, JPA/Hibernate, API REST, Cliente Web

---

## 1. Contexto y Dinámica del Trabajo

En este trabajo práctico, el grupo actuará como un equipo de desarrollo/consultoría y el docente asumirá el rol de Cliente.

El proyecto se desarrollará bajo la premisa del desarrollo asistido por Inteligencia Artificial (IA). Como estudiantes avanzados de 5to año, el foco principal del equipo no estará únicamente en la codificación línea por línea, sino en la especificación, diseño, validación y aseguramiento de calidad (QA) en tres niveles fundamentales:

1. **Calidad Funcional:** Asegurar que las historias de usuario y criterios de aceptación se cumplan rigurosamente.
2. **Calidad del Diseño:** Garantizar mantenibilidad, bajo acoplamiento, alta cohesión y uso de patrones adecuados.
3. **Calidad de la Arquitectura:** Asegurar extensibilidad, escalabilidad y tolerancia al cambio frente a la evolución del producto.

### La Regla del "Cliente Incierto"

El desarrollo se dividirá en **3 Etapas Iterativas**. Al inicio de cada etapa, el Cliente (docente) presentará nuevos requerimientos o cambios sobre la marcha. El equipo no conocerá con anticipación las solicitudes de las Etapas 2 y 3. El objetivo pedagógico es evaluar si las decisiones de arquitectura e implementación tomadas en las etapas previas soportan la evolución del sistema sin degradar la estabilidad ni requerir reescrituras masivas.

---

## 2. Requerimientos Iniciales (Etapa 1)

### Visión del Producto

El Cliente solicita el desarrollo de un **Servicio de Acortamiento y Gestión de Enlaces (Link Shortener)** orientado a simplificar y optimizar la compartición de URLs complejas (por ejemplo, enlaces extensos a carpetas compartidas de Google Drive, documentos en la nube, etc.).

### Funcionalidades Requeridas para la Etapa 1

1. **Acortamiento de URLs:**
* El sistema debe recibir una URL original válida y generar un enlace acortado único de la forma `http://{dominio_o_ip}/{alias}`.
* El alias o código resultante debe ser tan corto y memorizable como sea posible (ejemplos: `[http://192.168.1.50/xT3se]` o `[http://192.168.1.50/15321]`).
* Para el usuario, la generación del enlace acortado debe ser tan simple como sea posible. Para esto, se requieren dos opciones: una página web con un campo __dirección a acortar__ y un botón **ACORTAR**, y un complemento para Chome y Firefox, que con solo tocarlo genere y muestre la dirección acortada.
* Tanto la página web como el complemento deben generar y mostrar un código QR que codifique la dirección acortada. De este modo, se podrá llegar a la dirección original mediante la URL acortada o escaneando el código QR.
* La URL acortada debe ser válida durante 60 minutos. Luego dejará de redireccionar, quedará disponible y podrá ser reasignada. 


2. **Redirección:**
* Al acceder vía HTTP/HTTPS al enlace acortado, el sistema debe redirigir de forma transparente al usuario hacia la URL original.


3. **Persistencia de Datos:**
* Toda la información de los enlaces debe persistirse utilizando JPA/Hibernate sobre un motor de base de datos relacional.


4. **Arquitectura Cliente-Servidor:**
* **Backend:** API REST desarrollada en Java con Spring Boot.
* **Cliente:** Interfaz web que consuma la API REST para permitir a los usuarios acortar enlaces y visualizar sus resultados.



---

## 3. Metodología de Trabajo y Entregables

### Fases de Ejecución por Etapa

Para cada una de las 3 etapas, el equipo deberá cumplir con el siguiente ciclo:

1. **Elucidación y Descubrimiento:** Indagar con el Cliente (docente) los detalles funcionales, reglas de negocio no explícitas y restricciones técnicas mediante reuniones de requerimientos o minutas.
2. **Diseño y Especificación:** Definir los modelos de dominio, arquitectura de capas, contratos de la API REST (OpenAPI/Swagger) y prompts/especificaciones de ingeniería antes de la generación de código.
3. **Implementación Asistida por IA:** Utilizar herramientas de IA para la generación de código, pruebas y documentación.
4. **Verificación y Control de Calidad (QA):**
* Cobertura de pruebas unitarias y de integración.
* Verificación del cumplimiento de buenas prácticas REST y diseño de persistencia JPA.



---