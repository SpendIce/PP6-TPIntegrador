# Evidencia de evolución (issue #10)

Demostración del «experimento de adaptación» y la «revisión
arquitectónica» de la especificación padre (issue #1), siguiendo la
tabla «Cambios representativos» de
[arquitectura y validación](arquitectura-y-validacion.md) y la
«Evaluación de flexibilidad acordada» de
[requisitos de etapa 1](requisitos-etapa-1.md).

## Experimento real: otra duración para nuevas asignaciones

### Punto de variación

La duración no está fija en código: es configuración de despliegue.

- `application.yml`: `shortener.link-duration: ${LINK_DURATION:PT60M}`.
- `ShortenerProperties.linkDuration` (defecto `Duration.ofMinutes(60)`).
- `DomainConfig.expirationPolicy` construye `ExpirationPolicy` con ese
  valor: un único punto de cableado.
- `CreateLink` aplica la política **una vez al crear** y persiste el
  resultado: `vence_en = creada_en + duración`, en la fila de cada
  asignación.
- `ResolveLink` nunca consulta la política ni la configuración: la
  vigencia se evalúa comparando `clock.instant()` con el `vence_en`
  persistido (`Assignment.isActiveAt`).

Cambiar `link-duration` solo altera lo que se cree desde el cambio. Las
asignaciones anteriores conservan su `vence_en` porque es un dato
persistido por asignación, no un valor derivado al resolver.

### Procedimiento reproducible

El experimento no requiere tocar archivos: la variable de entorno basta.
Para observar el vencimiento en tiempo real se usa una duración corta
(`PT5M`); el mecanismo es idéntico para cualquier valor.

```bash
# Grupo A: duración de entrega (60 minutos, el default)
docker compose up -d
mvn spring-boot:run
curl -s -X POST localhost:8080/api/links \
  -H 'Content-Type: application/json' \
  -d '{"destination":"https://ejemplo.com/grupo-a"}'
# -> expiresAt = ahora + 60 min

# Cambio acotado: solo configuración de arranque, con reinicio
LINK_DURATION=PT5M mvn spring-boot:run
curl -s -X POST localhost:8080/api/links \
  -H 'Content-Type: application/json' \
  -d '{"destination":"https://ejemplo.com/grupo-b"}'
# -> expiresAt = ahora + 5 min

# Observación por API y por persistencia
curl -i localhost:8080/<aliasGrupoB>   # 302 primero; 404 a los 5 min
curl -i localhost:8080/<aliasGrupoA>   # sigue 302 hasta su hora
psql ... -c "SELECT alias_codigo, creada_en, vence_en FROM asignacion;"
# -> las filas del grupo A conservan vence_en = creada_en + 60 min

# Restitución para la entrega
mvn spring-boot:run                    # sin LINK_DURATION: vuelve PT60M
```

El cambio equivalente como diff de configuración (alternativa a la
variable de entorno) y su restitución:

```diff
 shortener:
   public-base-url: ${PUBLIC_BASE_URL:http://localhost:8080}
-  link-duration: ${LINK_DURATION:PT60M}
+  link-duration: ${LINK_DURATION:PT5M}
```

### Qué cambió y qué quedó intacto

| Eje | Resultado observado |
| --- | --- |
| Módulos | Ningún archivo Java modificado. El cambio vive solo en configuración de arranque. |
| Contratos | Sin cambios: `expiresAt` del contrato ya era por asignación; web y clientes no se tocan. |
| Datos | Sin migración: `asignacion.vence_en` ya persistía el instante calculado; filas anteriores intactas. |
| Identidades | Sin cambios: cada asignación conserva su id, alias, creación y vencimiento. |
| Pruebas | La suite completa existente permaneció en verde sin modificaciones. |
| Restitución | La entrega queda en 60 minutos: `application.yml` conserva `PT60M`; el experimento existió solo como argumento de arranque. |

### Evidencia automatizada

`src/test/java/io/github/spendice/linkshortener/EvolucionDuracionHttpTest.java`
reproduce el procedimiento sobre PostgreSQL real (Testcontainers,
ADR 0004) con reloj controlable (`MutableClock` vía
`ClockTestConfiguration`, solo en `src/test`: sin endpoint público de
reloj ni esperas de una hora).

Dos contextos Spring sucesivos sobre la misma base — el primero con
`PT60M`, el segundo con `PT5M` tras cerrarlo, igual que un reinicio con
la configuración cambiada — ejecutan esta línea temporal:

| Instante (reloj) | Acción / verificación |
| --- | --- |
| T0 | Contexto 1 (`PT60M`): crear A1 → `expiresAt` T0+60m en la respuesta. |
| T0+1m | Crear A2 → `expiresAt` T0+61m. A1 resuelve 302. |
| — | Cierre del contexto 1 y arranque del 2 con `PT5M` (el cambio acotado). |
| T0+2m | Crear B1 → `expiresAt` T0+7m. |
| T0+3m | Crear B2 → `expiresAt` T0+8m. |
| T0+5m | Los cuatro alias resuelven 302 a su destino. |
| T0+7m | B1 vence en su límite exacto (404 con la página acordada); B2, A1 y A2 siguen en 302. |
| T0+8m | B2 vence; A1 y A2 siguen en 302. |
| T0+60m | A1 vence en su límite exacto; A2 sigue un minuto más en 302. |
| T0+61m | A2 también vence (404). |

`cambiarLaDuracionNoRecalculaLasAsignacionesExistentes` verifica en la
base que cada fila conserva `creada_en`, `vence_en` y
`asignacion_actual_id` originales: el grupo A no se recalculó al
convivir con la nueva duración.
`cadaGrupoResuelveYVenceEnSuPropioInstantePersistido` ejecuta el barrido
de la tabla anterior: cada grupo vence en el instante que persistió al
crearse, evaluado por el mismo código de resolución que corre con la
otra duración configurada.

La prueba documenta un comportamiento ya diseñado (el punto de
variación existe desde el issue #3); su valor es la evidencia
ejecutable de que el cambio real es acotado y no altera lo existente.

## Análisis: alias personalizados (sin implementar)

Escenario «Alias personalizados» de la tabla «Cambios representativos»:
«entrada opcional en el contrato y política de reserva».

- **Contrato**: `CreateLinkRequest` ganaría un campo opcional `alias`.
  Es aditivo — los clientes que no lo envían siguen creando con alias
  automático — y hoy el esquema declara `additionalProperties: false`,
  por lo que el campo debe declararse explícitamente en `openapi.yaml`
  antes de implementar (misma disciplina de contrato primero). Hacen
  falta códigos de error nuevos para «alias inválido» (fuera del
  alfabeto, demasiado largo, ruta reservada) y «alias no disponible»
  (vigente en uso); ambos entran en el enum `ApiError` del contrato.
- **Módulos**: adaptador HTTP (`CreateLinkRequest` con el campo nuevo y
  `ApiExceptionHandler` mapeando los errores nuevos), `CreateLink` (si
  viene alias pedido, reservar ese código en lugar de `claimNewCode`),
  una regla de validación del alias en `domain` y una operación nueva
  del puerto `AliasStore` para reservar un código específico dentro de
  la misma transacción.
- **Datos**: no requiere migración: `alias.codigo` ya es PK y
  `VARCHAR(16)`, que es además el límite que el contrato debe respetar
  (el patrón actual admite hasta 32 en la ruta; un alias pedido más
  largo que la columna no podría persistirse — el máximo del campo
  nuevo debe fijarse con el esquema, no solo con el patrón).
- **Colisiones**: un alias pedido vigente se rechaza; uno vencido es
  una reasignación válida según las reglas de reutilización de ADR
  0002; uno inexistente se crea. Dos pedidos simultáneos del mismo
  código quedan serializados por la PK de `alias` dentro de la
  transacción: uno gana y el otro recibe el error de no disponible.
  Hallazgo concreto del código actual: `JpaAliasStore.claimNewCode`
  emite el siguiente código de la secuencia persistida sin consultar la
  tabla — válido hoy porque la secuencia es la única fuente de altas,
  pero si un alias personalizado ocupa un código que la secuencia aún
  no emitió, el generador lo alcanzaría después y chocaría con la PK.
  La reserva de alias pedidos debe entonces registrar el código como
  usado para el generador (p. ej., verificar existencia al emitir o
  marcar el salto), además de respetar `reserved-routes`.
- **Reutilización y regresiones**: la creación sin alias debe seguir
  idéntica (las pruebas actuales quedan verdes por contrato). Cuando el
  reciclaje de vencidos acordado en ADR 0002 esté implementado, el
  alias pedido debe componerse con esa prioridad sin saltearla para
  creaciones automáticas. Pruebas nuevas: alias válido aceptado,
  rechazo de inválido/reservado/vigente, reasignación de vencido,
  carrera de dos pedidos del mismo alias y compatibilidad de clientes
  que no envían el campo.

## Análisis: consola de gestión (sin implementar)

Escenario «Consola de gestión»: «nuevos casos de uso de consulta y un
cliente de gestión».

- **Módulos**: nuevos casos de uso de consulta en `application` (listar
  historial, obtener una asignación por id) con un puerto de consulta
  nuevo si la lectura difiere de `AssignmentStore`/`AliasStore`; nuevo
  adaptador HTTP con DTOs propios (la regla «no exponer entidades JPA»
  se mantiene); la consola es un cliente nuevo sobre la misma API, no
  un acoplamiento a la persistencia.
- **Contratos**: endpoints nuevos bajo una ruta propia. Si cuelga de
  `/api`, el código `api` ya está en `reserved-routes`; si se elige una
  ruta de primer nivel (p. ej. `/consola`), ese segmento debe agregarse
  a `shortener.reserved-routes` para que el generador nunca lo emita
  como alias, y `RedirectController` debe seguir sin capturarla. El
  contrato público actual no cambia.
- **Datos**: la consulta del historial no requiere migración — el
  modelo ya conserva id, alias, destino, creación y vencimiento por
  asignación. La consola debe listar **asignaciones** (identidad propia
  por id), no solo alias: varios registros pueden compartir
  `alias_codigo` en momentos distintos y son usos sucesivos distintos.
- **Edición o borrado**: si la consola permitiera modificar o eliminar
  asignaciones entraría en conflicto con ADR 0003 (conservar el
  historial, sin purga automática) — se documenta como conflicto
  potencial que requeriría revisar la decisión, no una implementación
  silenciosa. También exigiría reglas nuevas (¿qué significa borrar la
  asignación que `alias.asignacion_actual_id` referencia?) y,
  probablemente, autorización: las cuentas quedaron fuera de la etapa 1
  por acuerdo (Q4).
- **Pruebas**: las actuales no se ven afectadas (creación y resolución
  intactas); se agregarían pruebas de listado/detalle sobre la API con
  PostgreSQL real, verificando que la consola lee el historial sin
  alterar la resolución pública.

## Historial de asignaciones ≠ estadísticas

Distinción exigida por el AC: la tabla `asignacion` es el **historial
de asignaciones** — qué alias apuntó a qué destino, desde cuándo y
hasta cuándo vence. No registra visitas: la resolución es una consulta
de solo lectura y resolver no deja rastro persistido.

- Las visitas anteriores a una futura función de estadísticas **no se
  reconstruyen** desde este historial (ADR 0003 ya lo declara
  explícitamente). Conservar asignaciones no es conservar accesos.
- Si se agregaran estadísticas, cada visita debería registrarse contra
  la **identidad de la asignación** (`asignacion.id`), no contra el
  alias: el mismo alias sirve destinos distintos en usos sucesivos, y
  contar por alias mezclaría asignaciones diferentes. Reasignar no
  puede heredar las visitas de la asignación anterior.
- Módulos afectados si llegara el caso: un puerto nuevo de registro de
  visitas, una tabla `visita` con FK a `asignacion` (migración
  versionada), una escritura en el camino de resolución — hoy
  `readOnly` — que hay que evaluar contra el objetivo temporal
  (95 % ≤ 1 s) y las invariantes de concurrencia, y consultas agregadas
  para el cliente que las consuma.

## Qué demuestra el experimento sobre el monolito modular

Justificación pedida por el AC, medida en costo real:

- El cambio de comportamiento temporal costó **cero líneas de
  producción**: una propiedad de configuración y un reinicio. La
  facilidad no es casualidad sino consecuencia de dónde vive cada
  decisión: la regla está en `ExpirationPolicy` (dominio, sin imports
  de Spring), el cableado en un único bean (`DomainConfig`), y el
  resultado se persiste por asignación en `vence_en`.
- La resolución ignora la política por completo: compara el instante
  del servidor con el dato persistido. Por eso dos duraciones conviven
  en la misma base sin ramas condicionales, sin versionar esquema y sin
  recalcular nada.
- El reloj como puerto (`Clock` inyectado, `MutableClock` solo en test)
  permitió verificar los límites exactos de ambos grupos sin esperar
  una hora real y sin exponer un endpoint público de control temporal.
- Identidad de asignación separada del alias (ADR 0002) e historial
  conservado (ADR 0003) hicieron que «no recalcular lo existente» fuera
  la consecuencia natural del modelo, no un caso especial programado.
- Límite honesto de la evidencia: el experimento prueba el punto de
  variación **temporal**. Cambios que atraviesen contratos o esquema
  (alias personalizados tocan ambos) tienen un costo distinto que este
  experimento no mide; su análisis quedó documentado arriba en lugar de
  extrapolarse desde este resultado.

## Conflictos con ADR

- Experimento de duración: **ninguno**. Es exactamente el escenario
  «Otra duración para nuevas asignaciones» previsto en la tabla de
  cambios representativos, sobre el punto de variación diseñado.
- Consola de gestión con edición o borrado: conflicto potencial con
  ADR 0003 (conservación del historial); documentado en su sección —
  requeriría revisar la decisión antes de implementarse.
- Estadísticas: sin conflicto, pero ADR 0003 fija el límite conocido —
  las visitas pasadas no se reconstruyen del historial de asignaciones.
