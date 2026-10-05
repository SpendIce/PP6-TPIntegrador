# Acortador de enlaces — PP6 TP Integrador

Servicio de acortamiento de enlaces: API REST + web estática, monolito
modular en Java 17 / Spring Boot 3.5.16 con JPA/Hibernate sobre
PostgreSQL. Contrato en [openapi.yaml](openapi.yaml); decisiones en
[CONTEXT.md](CONTEXT.md), [docs/adr/](docs/adr/) y
[docs/especificacion-tecnica.md](docs/especificacion-tecnica.md).

## Requisitos

- Java 17 y Maven 3.9.x.
- Docker para PostgreSQL (desarrollo) y Testcontainers (pruebas).

## Ejecutar en desarrollo

```bash
docker compose up -d          # PostgreSQL 16 en localhost:5432
mvn spring-boot:run           # API + web en http://localhost:8080
```

La web queda en `GET /`, la creación en `POST /api/links` y la
resolución en `GET /{alias}`.

## Configuración de despliegue

| Variable | Propiedad | Defecto | Uso |
| --- | --- | --- | --- |
| `PUBLIC_BASE_URL` | `shortener.public-base-url` | `http://localhost:8080` | Dirección pública para componer `shortUrl`. Para la demo LAN usar la IP del host, p. ej. `http://192.168.1.50:8080`. |
| `OWN_ORIGINS` | `shortener.own-origins` | vacío | Orígenes propios extra (CSV, `esquema://host[:puerto]`) a rechazar como destino; se suman al origen público y a los equivalentes loopback. |
| `LINK_DURATION` | `shortener.link-duration` | `PT60M` | Duración de las nuevas asignaciones (ISO-8601). |
| `SERVER_PORT` | `server.port` | `8080` | Puerto HTTP. |
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USER` `DB_PASSWORD` | `spring.datasource.*` | `localhost` `5432` `acortador` | Conexión a PostgreSQL. |

Las rutas reservadas del generador de alias se configuran con
`shortener.reserved-routes` (por defecto `api` y `error`).

## Verificación

```bash
mvn test
```

Compila y corre las pruebas: unitarias de dominio y casos de uso, más el
recorrido HTTP completo sobre PostgreSQL real con Testcontainers
(creación, contrato de respuesta, persistencia, redirección 302/404,
rechazos del contrato y recursos estáticos de la web, incluido el QR).
No ejecutar `mvn package` ni `mvn verify` como verificación de cambios:
`mvn test` es la evidencia acordada.

Las pruebas del QR del cliente (`src/test/js/`) corren aparte con Node
y no intervienen en el build de Maven:

```bash
cd src/test/js && node --test
```

Generan el PNG real del QR y lo decodifican con bibliotecas
vendorizadas para comprobar que codifica el `shortUrl` exacto. El
procedimiento completo está en
[docs/verificacion-qr.md](docs/verificacion-qr.md).

## Esquema y migraciones

Flyway versiona el esquema desde `src/main/resources/db/migration/`.
`V1__esquema_inicial.sql` crea `alias`, `asignacion` (historial) y
`generador_alias` (avance persistente de la generación secuencial), con
la FK compuesta que garantiza que la referencia actual de cada alias
pertenece a ese mismo alias.
