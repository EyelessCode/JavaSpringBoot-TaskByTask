# Especificación de Requisitos: 000-foundation (Base Arquitectural y de Persistencia)

**Estado:** approved  
**Fecha:** 2026-11-09 (Aprobada por el usuario)  
**Autor:** SDD Planner Agent  
**Referencias:**
- `docs/constitution.md` (Principios 1, 2, 4, 5, 6)
- `docs/TaskByTask-SRS.md` (RF-01, RF-02, RF-03, RF-04, RF-05, RF-06, RF-07, RF-08, RF-09, RF-12, RF-13, RF-14, RF-15, RF-16, RF-19, RF-22, RF-27, RF-28, RF-29; RNF-01, RNF-02, RNF-04, RNF-05, RNF-06, RNF-07, RNF-08, RNF-09, RNF-10, RNF-14, RNF-18, RNF-19, RNF-20; Apéndice B, Apéndice C)
- `docs/TaskByTask-SVD.md` (OBJ-01, OBJ-02, CP-01, CP-02, CP-03, CP-04, CP-05, CP-06)
- `AGENTS.md` (Reglas de dominio, convenciones y mitigación de escollos)

---

## 1. Propósito y Justificación (Why)

El sistema Task by Task requiere una plataforma técnica sólida, segura y extensible que sostenga todas las capacidades funcionales del MVP (gestión de cuentas, grupos individuales, tareas, etiquetas y avisos de caducidad) y garantice la transición sin fricción ni migraciones destructivas hacia las fases posteriores (grupos compartidos hasta 5 usuarios, membresías con roles, asignación de tareas, subtareas y recordatorios).

Esta especificación fundacional define **qué** capacidades de infraestructura, persistencia, seguridad de sesión, manejo de errores y contratos de comunicación deben establecerse como base inmutable antes de implementar las funciones de negocio de los módulos verticales.

---

## 2. Alcance (Scope)

### 2.1 En Alcance (MVP Foundation)
- **Modelo de datos relacional extensible:** Definición del esquema inicial completo en el motor de persistencia relacional que soporte las entidades del MVP y los puntos de extensión de fases posteriores sin cambios estructurales destructivos (`RNF-20`).
- **Control de ciclo de vida de sesiones y autenticación:** Soporte para sesiones persistentes de hasta 7 días con revocación explícita, cookies seguras (`HttpOnly`, `SameSite=Lax`, `Secure`) y mitigación de falsificación de peticiones en sitios cruzados (CSRF).
- **Esquema de cifrado y funciones hash seguras:** Hashing criptográfico irreversible para credenciales y códigos de recuperación de un solo uso (`RNF-05`).
- **Contrato uniforme de respuestas de error (RFC 7807):** Estructura estándar de reporte de errores (`ProblemDetail`) en español ante fallos de autenticación (401), validación (400), conflicto de estado (409), denegación de permisos (403), recursos inexistentes (404) y saturación de peticiones (429) (`RNF-07`, `RNF-14`).
- **Protocolo de paginación por cursor (Keyset/Seek):** Contrato para recuperación progresiva y determinista de listados no acotados (tareas, grupos, etiquetas, notificaciones) (`RNF-04`).
- **Normalización temporal y de zonas horarias:** Almacenamiento temporal en UTC canónico, preservación de zona horaria local IANA y formato de hora (`24H` / `12H`) para cálculo de eventos y vencimientos (`RF-14`, `RF-28`, `RNF-14`).
- **Control de tasa de peticiones (Rate Limiting defensivo):** Límite para intentos de acceso con partición por cliente/IP y mitigación de fuerza bruta en memoria acotada (`RNF-06`).
- **Gestión segura de configuración y aislamiento de secretos:** Exclusión de credenciales y claves en el control de versiones y abastecimiento mediante variables de entorno.
- **Verificación automatizada con paridad de entorno:** Ejecución de pruebas contra una base de datos relacional real idéntica al entorno de producción para validar migraciones y mapeos.

### 2.2 Fuera de Alcance (Diferido a especificaciones funcionales o fases posteriores)
- Endpoints de registro, login, perfil y recuperación de contraseña (pertenecen a `001-auth-and-users`).
- Gestión de grupos individuales y compartidos (pertenecen a `002-groups`).
- Gestión de tareas, etiquetas, filtros y ordenamiento (pertenecen a `003-tasks-and-tags`).
- Envío de notificaciones push y ejecución de recordatorios (pertenecen a `004-notifications`).
- Servicio de correo electrónico transaccional y panel de analista (fases posteriores).

---

## 3. Requisitos del Sistema (EARS Format)

### 3.1 Persistencia y Modelo Relacional Extensible (RNF-20, Apéndice B)

- **FND-REQ-01 (Ubiquitous):** The system shall maintain a relational database schema containing the foundational entities: users, recovery codes, groups, group memberships, tasks, tags, task-tag associations, web push subscriptions, notifications, persistent sessions, and background task scheduling metadata.
- **FND-REQ-02 (Ubiquitous):** The system shall enforce non-destructive extensibility for shared collaboration (`RNF-20`) by defining:
  - Each group with a designated creator and a membership relationship supporting distinct roles (creator, manager, visitor).
  - A unique composite constraint on group memberships (`UNIQUE (group_id, user_id)`) preventing duplicate memberships of a user in the same group.
  - Each task with a group association and a nullable assigned user foreign key (`assigned_user_id`).
- **FND-REQ-03 (Ubiquitous):** The system shall enforce all field length, type, character and uniqueness constraints defined in SRS Appendix B:
  - User email: maximum 255 characters, unique, case-insensitive.
  - Username: 3 to 30 characters, alphanumeric (`[A-Za-z0-9]`), unique, case-insensitive.
  - Display name: 2 to 50 characters.
  - Plaintext password: 6 to 30 characters.
  - User preferred time format (`time_format`): enum or string with allowed values `24H` and `12H`, defaulting to `24H`.
  - Group name: 3 to 30 characters.
  - Tag name: 1 to 30 characters after trimming whitespace, strictly prohibiting semicolon (`;`) and comma (`,`) delimiters (`RF-22`), unique per group ignoring whitespace and letter case (`UNIQUE (group_id, TRIM(LOWER(name)))`).
  - Task title: 2 to 100 characters.
  - Task description: optional, maximum 750 characters.
  - Task due date: optional calendar date (`LocalDate`).
  - Task due time: optional time of day (`LocalTime`).
  - Temporal integrity constraint: due time requires due date (`CHECK (due_time IS NULL OR due_date IS NOT NULL)`), rejecting any task specification containing a due time without a due date.
- **FND-REQ-04 (Ubiquitous):** The system shall define a boolean flag on the group entity identifying the default "Personal" group (`is_personal = true`), backed by a relational partial unique index (`UNIQUE (creator_id) WHERE is_personal = true`) guaranteeing that every user has exactly one personal group, and preventing its deletion or conversion into a shared group (`RF-06`, `RF-07`).
- **FND-REQ-05 (Event-driven):** When a user account is deleted, the system shall cascade deletion to all owned groups, tasks, tags, recovery codes, push subscriptions, and active sessions, cancel all associated scheduled jobs (`task-due-{taskId}`) in the background scheduler, and ensure zero orphaned personal records (`RF-05`, `RNF-10`).
- **FND-REQ-06 (Event-driven):** When a non-personal group is deleted, the system shall cascade deletion to all contained tasks, tags, and group memberships, and cancel all associated scheduled jobs (`task-due-{taskId}`) in the background scheduler (`RF-07`).
- **FND-REQ-07 (State-driven):** While executing database schema changes, the system shall apply versioned, immutable, forward-only migrations and validate entity-schema alignment upon startup.
- **FND-REQ-08 (Ubiquitous):** The system shall maintain an automatic data retention rule for notifications, purging records older than 1 month (30 calendar days) from creation (`RF-29`, Apéndice B).

### 3.2 Sesiones, Seguridad y Criptografía (RF-01, RF-02, RF-03, RNF-05, RNF-08, RNF-09)

- **FND-REQ-09 (Ubiquitous):** The system shall store HTTP sessions in the persistent relational store, maintaining session state across application restarts and limiting active session lifetime to 7 calendar days (`RF-02`, `RNF-09`).
- **FND-REQ-10 (Ubiquitous):** The system shall issue session tokens exclusively in `HttpOnly`, `SameSite=Lax` cookies, with the `Secure` flag enabled on encrypted transport (`RNF-08`).
- **FND-REQ-11 (Event-driven):** When receiving state-mutating HTTP requests (POST, PUT, PATCH, DELETE) from authenticated sessions, the system shall validate an anti-CSRF token and reject requests lacking valid CSRF tokens with an unauthorized/forbidden response.
- **FND-REQ-12 (Ubiquitous):** The system shall hash user passwords and single-use recovery codes prior to database persistence using an adaptive one-way cryptographic hash algorithm (`{bcrypt}` as baseline), with support for zero-downtime hash upgrades upon authentication (`RNF-05`).
- **FND-REQ-13 (Event-driven):** When a user requests session logout or account deletion, the system shall invalidate the persistent session record immediately, preventing subsequent requests with that session identifier.

### 3.3 Control de Errores y Localización (RNF-07, RNF-14, RNF-18, RNF-19)

- **FND-REQ-14 (Ubiquitous):** The system shall format all HTTP error responses following RFC 7807 (`ProblemDetail`), including `type`, `title`, `status`, `detail`, `instance`, and a structured dictionary of validation errors when applicable.
- **FND-REQ-15 (Ubiquitous):** The system shall deliver all human-readable error descriptions (`title` and `detail`) exclusively in the Spanish language (`RNF-14`).
- **FND-REQ-16 (Unwanted event):** If an unauthenticated request attempts to access an endpoint requiring authentication, then the system shall return an HTTP 401 Unauthorized response containing a localized RFC 7807 problem detail (`title: "No autenticado"`, `detail: "Debe iniciar sesión para acceder a este recurso"`).
- **FND-REQ-17 (Unwanted event):** If an incoming request violates payload validation rules (length, format, nullability, missing due date when due time is present), then the system shall return an HTTP 400 Bad Request response containing the localized RFC 7807 problem detail with individual field-level error messages.
- **FND-REQ-18 (Unwanted event):** If a requested resource (user, group, task, tag) does not exist or has been deleted, then the system shall return an HTTP 404 Not Found response containing a localized RFC 7807 problem detail (`RNF-18`).
- **FND-REQ-19 (Unwanted event):** If a user attempts to access or modify a resource outside their authorized scope, then the system shall return an HTTP 403 Forbidden response containing a localized RFC 7807 problem detail.
- **FND-REQ-20 (Unwanted event):** If a request attempts an operation that violates entity lifecycle or state constraints (such as attempting to delete or convert the immutable "Personal" group), then the system shall return an HTTP 409 Conflict response containing a localized RFC 7807 problem detail (`title: "Conflicto de estado"`, `detail: "El grupo Personal no puede ser eliminado ni convertido en compartido"`).
- **FND-REQ-21 (Unwanted event):** If an unhandled unexpected runtime exception occurs, then the system shall log internal diagnostic details securely without exposing stack traces to the client and return an HTTP 500 Internal Server Error problem detail in Spanish.

### 3.4 Paginación por Cursor (RNF-04, RF-15)

- **FND-REQ-22 (Ubiquitous):** The system shall support Keyset (Seek) cursor-based pagination for all potentially unbounded resource collections (tasks, groups, tags, notifications) to guarantee stable $O(1)$ page traversal without offset degradation (`RNF-04`).
- **FND-REQ-23 (Ubiquitous):** The system shall encode pagination cursors as opaque, URL-safe, tamper-resistant tokens encapsulating the tie-breaker fields of the active sort order.
- **FND-REQ-24 (Ubiquitous):** The system shall enforce deterministic default task sort order:
  1. Completion state ascending (`completed ASC`, only applied when viewing "all" tasks).
  2. Due date ascending with nulls placed last (`due_date ASC NULLS LAST`).
  3. Due time ascending with nulls placed last (`due_time ASC NULLS LAST`), ensuring that on the same calendar date, tasks with specific times precede date-only tasks (which expire at `23:59:59.999`).
  4. Creation timestamp ascending (`created_at ASC`).
  5. Primary identifier ascending (`id ASC`).
- **FND-REQ-25 (Ubiquitous):** The system shall enforce deterministic sort orders and tie-breakers for the remaining paginated collections:
  - Groups: Personal group pinned first, followed by creation timestamp ascending and primary identifier (`is_personal DESC, created_at ASC, id ASC`).
  - Tags: Name ascending in group-scoped alphabetical order, followed by primary identifier (`name ASC, id ASC`).
  - Notifications: Creation timestamp descending (newest first), followed by primary identifier descending (`created_at DESC, id DESC`).

### 3.5 Tiempo, Fechas y Zonas Horarias (RF-12, RF-14, RF-28, RNF-14)

- **FND-REQ-26 (Ubiquitous):** The system shall record and persist all audit timestamps (`created_at`, `completed_at`, `last_accessed_at`) in canonical UTC (`Instant`).
- **FND-REQ-27 (Ubiquitous):** The system shall persist the user's local timezone as a valid IANA Time Zone identifier (`ZoneId`), defaulting to `UTC` if unspecified, and user's preferred time display format (`time_format`: `24H` default, `12H`).
- **FND-REQ-28 (Ubiquitous):** The system shall distinguish between tasks with date-only due dates (`LocalDate`) and tasks with date-and-time due dates (`LocalDate` + `LocalTime`), preserving date-only tasks without coercing time to midnight (`00:00:00`).
- **FND-REQ-29 (Ubiquitous):** The system shall determine overdue state (`RF-14`) such that:
  - Tasks with a specific due time become overdue immediately after that instant in the user's timezone.
  - Tasks with date-only due dates become overdue only after `23:59:59.999` of that date in the user's local timezone.
- **FND-REQ-30 (Ubiquitous):** The system shall calculate scheduled notification trigger moments (`RF-28`) such that:
  - Tasks with a specific due time trigger at that exact instant.
  - Tasks with date-only due dates for future calendar dates trigger at `09:00:00 AM` on that date in the user's local timezone.
  - Tasks with date-only due dates set for the current day ("today" in the user's local timezone) created or edited after `09:00:00 AM` local time trigger the due notification immediately, while remaining pending and not overdue until `23:59:59.999`.
- **FND-REQ-31 (Event-driven):** When a task is marked as completed (`completed = true`), deleted, or when its due date/time is removed or rescheduled, the system shall cancel or reschedule the corresponding background job `task-due-{taskId}` in the scheduler deterministically (`RF-13`, `RF-14`, `RF-28`).

### 3.6 Tasa de Peticiones y Mitigación de Fuerza Bruta (RNF-06)

- **FND-REQ-32 (Ubiquitous):** The system shall maintain an in-memory rate limiting mechanism for authentication endpoints to restrict consecutive failed access attempts, partitioned by client IP address (or account identifier):
  - Standard capacity: maximum 5 attempts per 1-minute refill window for authentication endpoints.
  - Bounded memory footprint: managed via an in-memory cache with an explicit maximum size (maximum 10,000 entries) and time-to-live (`expireAfterAccess` of 10 minutes) to prevent memory exhaustion without incurring database IOPS cost.
- **FND-REQ-33 (Unwanted event):** If requests to rate-limited authentication endpoints exceed the allowed threshold, then the system shall return an HTTP 429 Too Many Requests response with a localized RFC 7807 problem detail indicating the remaining wait duration.

### 3.7 Aislamiento de Secretos y Configuración (docs/constitution.md)

- **FND-REQ-34 (Ubiquitous):** The system shall prohibit hardcoded database credentials, cryptographic keys, or third-party tokens in source code and version-controlled configuration files.
- **FND-REQ-35 (Ubiquitous):** The system shall load sensitive persistence and security parameters through environment variables, supporting local development environment files explicitly ignored by source control.

### 3.8 Verificación Automatizada y Paridad de Base de Datos (docs/constitution.md)

- **FND-REQ-36 (Ubiquitous):** The system shall execute integration test suites against a real containerized PostgreSQL instance matching production database behavior, disallowing in-memory mock databases (such as H2).
- **FND-REQ-37 (Ubiquitous):** The system shall verify schema migration success and entity-mapping validity upon context initialization in all test suites (`ddl-auto=validate`).

---

## 4. Casos Borde y Condiciones de Fallo (Edge Cases)

- **EC-01: Tareas con fecha vencida y tareas de solo fecha creadas después de las 09:00 AM:**
  - Si una tarea se crea con fecha/hora de caducidad pasada, es válida según `RF-12`. El sistema la almacena con estado pendiente y nace marcada como vencida, sin programar alertas futuras.
  - Si una tarea con solo fecha se crea o edita para el día de hoy (en la zona horaria del usuario) cuando ya han pasado las `09:00:00 AM` locales, el aviso de caducidad se dispara de inmediato, mientras que la tarea permanece no vencida hasta las `23:59:59.999` locales (`RF-28`).
- **EC-02: Transición de fecha, completado y eliminación de tareas:**
  - Al reprogramar o quitar la fecha de una tarea, el trabajo `task-due-{taskId}` en el planificador se reprograma o cancela de inmediato.
  - Al marcar una tarea como completada o al eliminarla (individualmente, por borrado de grupo o por eliminación de cuenta de usuario), cualquier trabajo `task-due-{taskId}` programado se cancela atómicamente (`RF-13`).
- **EC-03: Reutilización transparente de etiquetas duplicadas:** Si un usuario asigna o crea una etiqueta cuyo nombre ya existe en el grupo (ignorando mayúsculas y espacios en extremos, ej. `" Urgente "` vs `"urgente"`), el sistema reutiliza la etiqueta existente de forma transparente sin emitir advertencias ni errores (`RF-22`).
- **EC-04: Eliminación o conversión del grupo "Personal":** Cualquier intento directo o indirecto de eliminar o convertir el grupo marcado como personal (`is_personal = true`) es rechazado de forma unívoca con un error `HTTP 409 Conflict` indicando que el grupo por defecto es inmutable (`RF-07`).
- **EC-05: Expiración simultánea de sesión HTTP y token CSRF:** Si la sesión caduca o es purgada del almacén persistente, toda petición subsecuente a endpoints protegidos retorna `HTTP 401 Unauthorized` bajo formato RFC 7807 sin revelar detalles internos del servidor.
- **EC-06: Formato de zona horaria IANA inválido o no reconocido:** Si una solicitud envía una zona horaria que no coincide con un identificador válido de la base IANA (ej. `America/Bogota`), el sistema rechaza el valor con un error `HTTP 400 Bad Request` RFC 7807 en español.
- **EC-07: Inclusión de hora de caducidad sin fecha (`due_time` sin `due_date`):** Si una solicitud incluye `due_time` pero omite `due_date`, el sistema rechaza la petición con `HTTP 400 Bad Request` RFC 7807 indicando que la hora exige especificar una fecha.

---

## 5. Matriz de Trazabilidad

| Requisito EARS | Requisito SRS / Constitución | Criterio de Aceptación Asociado |
| :--- | :--- | :--- |
| **FND-REQ-01** | Apéndice B, RNF-20 | El esquema relacional crea las tablas base para usuarios, grupos, tareas, etiquetas, sesiones y colas. |
| **FND-REQ-02** | RNF-20, RF-08, RF-09, RF-19 | Estructura extensible con roles, clave asignada nullable y restricción `UNIQUE (group_id, user_id)`. |
| **FND-REQ-03** | Apéndice B, RF-12, RF-22, RNF-19 | Columnas con límites del SRS, integridad `due_time -> due_date`, contraseñas 6-30 chars y etiquetas sin `;` ni `,`. |
| **FND-REQ-04** | RF-06, RF-07 | Grupo personal protegido por bandera booleana e índice único parcial `WHERE is_personal = true`. |
| **FND-REQ-05** | RF-05, RF-13, RNF-10 | Borrar usuario elimina en cascada sus recursos y cancela sus jobs `task-due-{taskId}` en el scheduler. |
| **FND-REQ-06** | RF-07, RF-13 | Borrar grupo elimina tareas/etiquetas en cascada y cancela jobs `task-due-{taskId}` en el scheduler. |
| **FND-REQ-07** | Constitución Principio 2, 6 | Migraciones versionadas forward-only y validación JPA `ddl-auto=validate`. |
| **FND-REQ-08** | RF-29, Apéndice B | Retención automática de notificaciones por 1 mes (30 días) y purga de registros antiguos. |
| **FND-REQ-09** | RF-02, RNF-09 | Sesiones guardadas en PostgreSQL que expiran a los 7 días. |
| **FND-REQ-10** | RNF-08 | Cookies de sesión `HttpOnly`, `SameSite=Lax` y seguras en HTTPS. |
| **FND-REQ-11** | Constitución Principio 5 | Validación de token anti-CSRF para peticiones mutables. |
| **FND-REQ-12** | RNF-05, RF-01, RF-03 | Hashes adaptativos no reversibles (`{bcrypt}`) para contraseñas y códigos de recuperación. |
| **FND-REQ-13** | RF-02, RF-05 | Cierre de sesión y baja de usuario invalidan la sesión en PostgreSQL inmediatamente. |
| **FND-REQ-14** | RNF-07, RNF-18 | Errores formateados según RFC 7807 `ProblemDetail`. |
| **FND-REQ-15** | RNF-14 | Mensajes de error para usuarios redactados exclusivamente en español. |
| **FND-REQ-16** | RNF-07 | Peticiones no autenticadas devuelven HTTP 401 RFC 7807 en español. |
| **FND-REQ-17** | RNF-07, Apéndice B | Violaciones de validación generan HTTP 400 con mapa de errores de campo. |
| **FND-REQ-18** | RNF-18 | Recursos no encontrados devuelven HTTP 404 RFC 7807 en español. |
| **FND-REQ-19** | RNF-07 | Peticiones no autorizadas devuelven HTTP 403 RFC 7807 en español. |
| **FND-REQ-20** | RF-07, RNF-07 | Intentos de alterar/eliminar el grupo Personal devuelven HTTP 409 Conflict RFC 7807. |
| **FND-REQ-21** | RNF-07 | Errores inesperados generan HTTP 500 RFC 7807 sin exponer trazas internas. |
| **FND-REQ-22** | RNF-04 | Colecciones no acotadas implementan Keyset pagination. |
| **FND-REQ-23** | RNF-04 | Cursores opacos y seguros para transporte en URL. |
| **FND-REQ-24** | RF-15, AGENTS.md | Ordenamiento determinista de tareas: `completed ASC` (en "todas"), `due_date ASC NULLS LAST`, `due_time ASC NULLS LAST`, `created_at ASC`, `id ASC`. |
| **FND-REQ-25** | RNF-04 | Ordenamiento determinista y desempate para grupos, etiquetas y notificaciones. |
| **FND-REQ-26** | RF-12, RNF-14 | Almacenamiento canónico de marcas de tiempo en UTC. |
| **FND-REQ-27** | RF-01, RF-04, RNF-14 | Preservación de `ZoneId` y formato horario (`24H` / `12H`). |
| **FND-REQ-28** | RF-14, RF-28 | Diferenciación entre tareas de solo fecha y tareas con hora. |
| **FND-REQ-29** | RF-14 | Vencimiento: tareas con hora al instante; de solo fecha a las 23:59:59.999 local. |
| **FND-REQ-30** | RF-28 | Avisos: con hora al instante; de solo fecha a las 09:00 AM local; si es hoy después de 09:00 AM, inmediato. |
| **FND-REQ-31** | RF-13, RF-14, RF-28 | Cancelación y reprogramación de jobs `task-due-{taskId}` al cambiar estado, fecha o borrar. |
| **FND-REQ-32** | RNF-06 | Rate limiting con Bucket4j + Caffeine particionado por cliente/IP con límites acotados. |
| **FND-REQ-33** | RNF-06 | Peticiones que exceden el límite de tasa devuelven HTTP 429 Too Many Requests. |
| **FND-REQ-34** | Constitución Principio 5 | Cero credenciales o secretos en archivos versionados. |
| **FND-REQ-35** | Constitución Principio 5 | Inyección de secretos mediante variables de entorno y soporte `.env` ignorado. |
| **FND-REQ-36** | Constitución Principio 6 | Pruebas de integración con contenedor PostgreSQL real (`postgres:alpine`). |
| **FND-REQ-37** | Constitución Principio 6 | Validación de mapeo JPA (`ddl-auto=validate`) en pruebas de arranque. |
