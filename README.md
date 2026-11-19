# Task by Task — Backend API (`v1.0.0-mvp`)

API REST en **Java 21 + Spring Boot 4.1.2** para **Task by Task**, una plataforma web en español enfocada en organizar tareas personales por grupos y etiquetas, aplicar filtros combinados con paginación Keyset y recibir notificaciones in-app y Web Push por navegador al vencimiento de tareas.

## Alcance MVP y Documentación

Este repositorio implementa el alcance **MVP** completo dividido en 6 módulos verticales bajo `com.eyelesscode.task_by_task`:

| Módulo | Responsabilidad en el MVP |
| :--- | :--- |
| `auth` | Registro (`POST /api/v1/auth/register`), login (`POST /api/v1/auth/login`), logout selectivo (`POST /api/v1/auth/logout`), recuperación con códigos de un solo uso (`POST /api/v1/auth/recover-password`) y bootstrap CSRF (`GET /api/v1/auth/csrf`). |
| `user` | Consulta y edición de perfil (`/api/v1/users/me`), cambio de contraseña, regeneración de los 8 códigos de recuperación y eliminación permanente de cuenta. |
| `group` | CRUD de grupos individuales (`/api/v1/groups`) con grupo predeterminado `"Personal"` siempre primero y protegido contra eliminación (`409`). |
| `tag` | Creación/reutilización idempotente y borrado de etiquetas por grupo (`/api/v1/groups/{groupId}/tags`) con unicidad `TRIM(LOWER(name))`. |
| `task` | Gestión de tareas (`/api/v1/tasks`, `/api/v1/groups/{groupId}/tasks`), `PATCH` parcial, mover/copiar entre grupos, desasociar etiquetas, filtros combinados y paginación Keyset. |
| `notification` | Suscripciones Web Push VAPID (`/api/v1/notifications/push/*`), programación de avisos (`09:00 AM` en tareas solo-fecha; vencimiento visual a las `23:59:59.999` hora local), centro de notificaciones y retención de 30 días. |

- Documento de Visión: [`docs/TaskByTask-SVD.md`](docs/TaskByTask-SVD.md)
- Especificación de Requisitos (SRS): [`docs/TaskByTask-SRS.md`](docs/TaskByTask-SRS.md)
- Especificaciones SDD implementadas (`000`–`005`): [`docs/specs/`](docs/specs/) (`000-foundation`, `001-auth-and-users`, `002-groups`, `003-tasks-and-tags`, `004-notifications`, `005-hardening`)

## Stack Técnico y Arquitectura

- **Lenguaje y estilo:** Pure Java 21 con `record` para DTOs, inyección por constructor y mapeadores manuales (sin Lombok ni MapStruct), formateado con **Spotless**.
- **Framework:** Spring Boot `4.1.2` (Web MVC, Spring Data JPA, Validation, Actuator).
- **Base de datos y migraciones:** PostgreSQL (Supabase esquema `public`), **Flyway** (`V1__init_schema.sql`), JPA `ddl-auto: validate` y pool **HikariCP** acotado (`maximum-pool-size: 10`, `minimum-idle: 2`).
- **Seguridad y sesiones:** Spring Security + **Spring Session JDBC** (cookie `SESSION` `HttpOnly` de 7 días), protección CSRF (`XSRF-TOKEN` / `X-XSRF-TOKEN`), CORS sin comodines, frontera de proxies de confianza (`TrustedProxyResolver` + `TrustedForwardedHeaderFilter`) y rate limiting en memoria con **Bucket4j + Caffeine**.
- **Notificaciones y tareas programadas:** `db-scheduler` (`15.0.0`) y Web Push cifrado (**BouncyCastle `bcprov-jdk18on`** RFC 8291 / VAPID RFC 8292).
- **Contrato y observabilidad:** Errores **RFC 7807** (`application/problem+json`) en español, **OpenAPI 3 / Swagger UI** (`springdoc-openapi` `3.2.0`), apagado elegante (`graceful`, `25s`) y **Actuator** endurecido.

## Desarrollo Local

### 1. Levantar PostgreSQL local

El archivo [`docker-compose.dev.yml`](docker-compose.dev.yml) levanta PostgreSQL `postgres:alpine` en el puerto `5433` (por defecto `taskbytask_dev` / `taskbytask_dev` / `taskbytask_dev_local`).

```bash
cp .env.example .env.dev
# Ajustar DEV_DB_URL=jdbc:postgresql://localhost:5433/taskbytask_dev, DEV_DB_USERNAME y DEV_DB_PASSWORD
docker compose -f docker-compose.dev.yml --env-file .env.dev up -d
```

### 2. Ejecutar la aplicación en perfil `dev`

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

En `dev`:
- Swagger UI: `http://localhost:8080/swagger-ui/index.html` y OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Salud Actuator: `http://localhost:8080/actuator/health`
- CORS por defecto: `http://localhost:3000,http://localhost:5173` (`COOKIE_SECURE=false`)

### 3. Formato, compilación y pruebas

```bash
./mvnw spotless:check compile test-compile
./mvnw test
```

La suite completa ejecuta **263 pruebas** unitarias e integración contra PostgreSQL real usando **Testcontainers** (`postgres:alpine`, requiere Docker activo).

## Despliegue en Producción con Docker

El [`Dockerfile`](Dockerfile) multi-stage genera una imagen mínima sobre `eclipse-temurin:21-jre-alpine` ejecutándose con usuario sin privilegios `app`, `SPRING_PROFILES_ACTIVE=prod`, puerto dinámico `${PORT:-8080}` y `HEALTHCHECK` integrado — compatible con **Render, Railway, Fly.io o VPS**.

```bash
docker build -t task-by-task-api:v1.0.0-mvp .

docker run --rm -p 8080:8080 \
  --env-file .env \
  task-by-task-api:v1.0.0-mvp
```

### Variables de entorno (`.env.example`)

Tome [`.env.example`](.env.example) como plantilla (nunca versione archivos `.env` con credenciales reales):

| Variable | Valor por defecto (`prod`) | Descripción / Advertencia |
| :--- | :--- | :--- |
| `SPRING_PROFILES_ACTIVE` | `prod` | Perfil activo (`dev` \| `prod`). En `Dockerfile` viene fijado a `prod`. |
| `PORT` | `8080` | Puerto HTTP de escucha (inyectado automáticamente por Render/Railway/Fly.io). |
| `DB_URL` | `jdbc:postgresql://localhost:5432/postgres` | **Use SIEMPRE Supabase Session Mode (puerto `5432`) o conexión directa.** Nunca use Transaction Pooler (`6543`), ya que Flyway requiere *advisory locks* de PostgreSQL. |
| `DB_USERNAME` / `DB_PASSWORD` | `postgres` / vacío | Credenciales de PostgreSQL (esquema `public`). |
| `DB_POOL_MAX_SIZE` / `DB_POOL_MIN_IDLE` | `10` / `2` | Límites de HikariCP (`connection-timeout=5000`, `idle-timeout=300000`, `max-lifetime=1500000`). |
| `CORS_ALLOWED_ORIGINS` | vacío | Orígenes permitidos separados por comas. **Prohibido usar `*`** (la API falla al arrancar si detecta `*`). |
| `CORS_MAX_AGE_SECONDS` | `3600` | Caché de respuestas preflight CORS. |
| `COOKIE_SECURE` | `true` | Marca `Secure` en cookies `SESSION` y `XSRF-TOKEN`. |
| `COOKIE_SAME_SITE` | `Lax` | `Lax` \| `Strict` \| `None`. Si usa `None` (front y back en distintos *sites*), `COOKIE_SECURE` **debe** ser `true` (validado al arranque). |
| `COOKIE_DOMAIN` | vacío | Dominio opcional compartido para las cookies (vacío = host actual). |
| `TRUSTED_PROXIES` | `127.0.0.1/32,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,fc00::/7` | CIDRs IPv4/IPv6 de proxies inversos confiables cuyas cabeceras `X-Forwarded-*` / `X-Real-IP` se aceptan. |
| `OPENAPI_ENABLED` / `SWAGGER_UI_ENABLED` | `false` / `false` | Deshabilitados por defecto en `prod` (devuelven `404` RFC 7807 en español). |
| `WEB_PUSH_ENABLED` | `false` | Activa el envío de notificaciones Web Push del navegador. |
| `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` / `VAPID_SUBJECT` | vacío | Par de claves EC P-256 (Base64URL) y sujeto `mailto:...` para VAPID (RFC 8292). |

### Probes de Actuator en producción

Solo el endpoint `health` está habilitado (`management.endpoints.access.default: none`, `show-details: never`, cualquier otro `/actuator/**` responde `401`/`403`):

- `GET /actuator/health` — estado general
- `GET /actuator/health/liveness` — sonda *liveness* (usada por `HEALTHCHECK`)
- `GET /actuator/health/readiness` — sonda *readiness* (`readinessState,db`)

## Resumen de Integración Frontend

1. **Credenciales en todas las peticiones:** configure `fetch(..., { credentials: "include" })` o `axios.defaults.withCredentials = true` para enviar y recibir las cookies `SESSION` y `XSRF-TOKEN`.
2. **Bootstrap y envío de CSRF:** antes de mutaciones (`POST`, `PATCH`, `PUT`, `DELETE`, incluyendo `/api/v1/auth/register`, `/login` y `/recover-password`), llame a `GET /api/v1/auth/csrf` y reenvíe el token en la cabecera `X-XSRF-TOKEN`.
3. **Paginación Keyset:** los listados (`groups`, `tags`, `tasks`, `notifications`) usan parámetros `cursor` y `limit` (`1` a `50`, por defecto `20`) y devuelven `{ content, nextCursor, hasNext, size }`.
4. **Errores RFC 7807:** todas las respuestas `4xx`/`5xx` devuelven `Content-Type: application/problem+json` con `title`, `status`, `detail`, `instance` en español (además de `invalidParams` en `400` de validación y cabecera `Retry-After` / `retryAfterSeconds` en `429`).
