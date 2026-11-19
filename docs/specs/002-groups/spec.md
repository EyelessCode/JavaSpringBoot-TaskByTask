# Especificación de Requisitos: 002-groups (Gestión de Grupos Individuales y Grupo "Personal")

**Estado:** approved  
**Fecha:** 2026-11-15 (Aprobada por el usuario)  
**Autor:** SDD Planner Agent  
**Referencias:**
- `docs/constitution.md` (Principios 1, 2, 3, 4, 5, 6)
- `docs/TaskByTask-SRS.md` (RF-06, RF-07, RF-24; RNF-04, RNF-07, RNF-14, RNF-17, RNF-18, RNF-19, RNF-20; Apéndice B)
- `docs/TaskByTask-SVD.md` (CP-03, Sección 3.1)
- `docs/specs/000-foundation/spec.md` (FND-REQ-01 a FND-REQ-06, FND-REQ-11, FND-REQ-14 a FND-REQ-25)
- `docs/specs/001-auth-and-users/spec.md` (AUTH-REQ-05: creación inicial del grupo `"Personal"` en el registro)
- `AGENTS.md` (Reglas de dominio, extensibilidad RNF-20 e inmutabilidad del grupo `"Personal"`)
- `MEMORY.md` (Estado del proyecto y decisiones arquitecturales)

---

## 1. Propósito y Justificación (Why)

El módulo **002-groups** provee la estructura organizativa fundamental de **Task by Task**, permitiendo a cada usuario clasificar y aislar sus tareas pendientes y etiquetas dentro de contenedores lógicos llamados **grupos** (`RF-06`, `RF-07`, `RF-24`).

Desde el momento del registro (`001-auth-and-users`), todo usuario cuenta con un grupo por defecto denominado `"Personal"` (`is_personal = true`). Este módulo extiende esa base para que el usuario pueda:
1. Crear tantos grupos individuales adicionales (`is_personal = false`) como necesite, sin límites artificiales de cantidad.
2. Consultar su catálogo de grupos de forma progresiva y determinista mediante paginación por cursor Keyset (`RNF-04`), garantizando que su grupo `"Personal"` aparezca siempre fijado en primer lugar (`is_personal DESC, created_at ASC, id ASC`).
3. Renombrar cualquiera de sus grupos —incluido el grupo `"Personal"`— manteniendo intactas sus propiedades estructurales.
4. Eliminar grupos individuales que ya no requiera junto con todas sus tareas, etiquetas y avisos programados en cascada (`RF-07`), protegiendo de forma estricta e inmutable el grupo `"Personal"` frente a cualquier intento de eliminación (`HTTP 409 Conflict`).

En cumplimiento con el principio de extensibilidad sin migraciones destructivas (`RNF-20`), cada grupo individual creado registra desde su origen a su autor como creador (`creator_id`) y genera atómicamente su fila correspondiente en `group_memberships` (`role = 'CREATOR'`), dejando el modelo listo para soportar grupos compartidos en fases posteriores sin alterar los contratos ni los datos del MVP.

---

## 2. Alcance (Scope)

### 2.1 En Alcance (Fase 1 / MVP)
- **Creación de grupos individuales (`RF-06`, `RNF-20`, Apéndice B via `POST /api/v1/groups`):**
  - Validación de `name` (obligatorio, de 3 a 30 caracteres tras recortar espacios externos con `trim()`).
  - Creación del grupo con `is_personal = false` y `creator_id` igual al usuario autenticado, ignorando cualquier propiedad desconocida o controlada por el servidor en el payload JSON.
  - Creación atómica de la membresía inicial en `group_memberships` con `role = 'CREATOR'`.
  - Sin límite en la cantidad de grupos individuales creados por usuario y sin restricción de unicidad sobre el nombre del grupo.
  - Respuesta `HTTP 201 Created` con la representación completa del grupo creado (`id`, `name`, `isPersonal`, `creatorId`, `createdAt`, `updatedAt`).
- **Consulta y listado paginado de grupos (`RF-06`, `RF-24`, `RNF-04`, `RNF-18`, `FND-REQ-18`, `FND-REQ-19`):**
  - Listado paginado Keyset (`GET /api/v1/groups`) con parámetros opcionales `cursor` y `limit` (por defecto `20`, mínimo `1`, máximo `50`), ordenado estrictamente por `(is_personal DESC, created_at ASC, id ASC)` usando `KeysetCursor.GroupCursor(isPersonal, createdAt, id)` y `KeysetCursorCodec`, garantizando que el grupo `"Personal"` aparezca siempre primero en la estructura `KeysetPage` (`content`, `nextCursor`, `hasNext`, `size`).
  - Consulta de detalle de un grupo individual o `"Personal"` por identificador (`GET /api/v1/groups/{groupId}`), retornando `404 Not Found` si no existe y `403 Forbidden` si pertenece a otro usuario.
- **Renombrado de grupos (`RF-06`, `RF-07`, Apéndice B via `PATCH /api/v1/groups/{groupId}`):**
  - Actualización del nombre para cualquier grupo perteneciente al usuario autenticado, **incluido** el grupo `"Personal"` (cuya bandera `is_personal = true` permanece inmutable), validando longitud de 3 a 30 caracteres tras `trim()` y actualizando `updated_at`.
- **Eliminación de grupos e inmutabilidad del grupo `"Personal"` (`RF-06`, `RF-07`, `FND-REQ-04`, `FND-REQ-06`, `FND-REQ-20` via `DELETE /api/v1/groups/{groupId}`):**
  - Rechazo con `HTTP 409 Conflict` (`ProblemDetail` en español con `title: "Conflicto de estado"` y `detail: "El grupo Personal no puede ser eliminado ni convertido en compartido"`) cuando el grupo objetivo pertenece al usuario autenticado y tiene `is_personal = true`.
  - Para grupos individuales (`is_personal = false`), bloqueo pesimista de la fila del grupo, cancelación transaccional de cualquier trabajo programado pendiente (`task-due-{taskId}`) en `scheduled_tasks` asociado a tareas del grupo antes del borrado en cascada (`tasks`, `tags`, `task_tags`, `group_memberships`), retornando `HTTP 204 No Content`.
- **Seguridad, autorización y errores RFC 7807 en español (`RNF-07`, `RNF-14`, `RNF-18`, `RNF-19`, `FND-REQ-11`, `FND-REQ-16` a `FND-REQ-20`):**
  - `HTTP 401 Unauthorized` sin sesión activa.
  - `HTTP 403 Forbidden` ante peticiones mutables (`POST`, `PATCH`, `DELETE`) sin token CSRF válido (`FND-REQ-11`) o ante intentos de acceder, renombrar o eliminar un grupo perteneciente a otro usuario (`FND-REQ-19`).
  - `HTTP 404 Not Found` cuando el `groupId` solicitado no existe o ya fue eliminado (`FND-REQ-18`, `RNF-18`).
  - `HTTP 400 Bad Request` ante nombres inválidos (`invalidParams`), límites fuera de rango (`1..50`), cursores manipulados o incompletos, parámetros de ruta/consulta con tipo inválido o cuerpos JSON malformados (`FND-REQ-17`).

### 2.2 Fuera de Alcance (Diferido a especificaciones funcionales o fases posteriores)
- Conversión de grupos individuales en grupos compartidos (2 a 5 personas) y reversión a grupo individual (`RF-08`, Fase Posterior).
- Roles activos de colaboración (`MANAGER`, `VISITOR`), matriz de permisos compartidos, expulsión de miembros y abandono de grupo (`RF-09`, Fase Posterior).
- Invitaciones a grupos dentro de la plataforma o por correo electrónico con caducidad de 24 horas (`RF-10`, `RF-11`, Fase Posterior).
- Notificaciones a miembros por eliminación de grupos compartidos (`RF-30`, Fase Posterior).
- Operaciones CRUD de tareas, subtareas, etiquetas, movimiento/copia de tareas entre grupos y filtrado de tareas (`RF-12` a `RF-25`) — corresponden a `003-tasks-and-tags`.

---

## 3. Requisitos del Sistema (EARS Format)

### 3.1 Creación de Grupos Individuales (RF-06, RNF-20, Apéndice B)

- **GRP-REQ-01 (Event-driven):** When an authenticated user submits a group creation request to `POST /api/v1/groups` containing a valid `name` (ignoring any unknown or server-managed JSON properties such as `isPersonal`), the system shall trim leading and trailing whitespace (`trim()`), persist a new group record with `creator_id` set to the authenticated user's ID, `is_personal = false`, and current UTC `created_at` and `updated_at` timestamps, and return an HTTP 201 Created response containing the created group representation (`id`, `name`, `isPersonal`, `creatorId`, `createdAt`, `updatedAt`).
- **GRP-REQ-02 (Event-driven):** When creating a new individual group via `POST /api/v1/groups`, the system shall atomically create a corresponding membership record in `group_memberships` linking the new group and the authenticated user with `role = 'CREATOR'` and current UTC `joined_at` timestamp, satisfying `RNF-20`.
- **GRP-REQ-03 (Ubiquitous):** The system shall allow an authenticated user to create an unlimited number of individual groups (`is_personal = false`) without enforcing uniqueness constraints on the group `name` (`RF-06`, Apéndice B).
- **GRP-REQ-04 (Ubiquitous):** The system shall enforce group name boundaries according to SRS Appendix B on both creation (`POST /api/v1/groups`) and rename (`PATCH /api/v1/groups/{groupId}`) requests: the `name` field shall be required, non-blank, and between 3 and 30 characters in length after trimming leading and trailing whitespace (`3 <= char_length(TRIM(name)) <= 30`).
- **GRP-REQ-05 (Unwanted event):** If a group creation request to `POST /api/v1/groups` contains a missing, blank, or invalid `name` (fewer than 3 or more than 30 characters after trimming), then the system shall reject the request with an HTTP 400 Bad Request status and an RFC 7807 problem detail in Spanish (`title: "Error de validación"`, `detail: "Uno o más campos contienen errores de validación"`) including an `invalidParams` list with the validation error for `"name"`.

### 3.2 Consulta y Listado Paginado de Grupos (RF-06, RF-24, RNF-04, RNF-18, FND-REQ-18, FND-REQ-19)

- **GRP-REQ-06 (Event-driven):** When an authenticated user requests their groups via `GET /api/v1/groups` with an optional `cursor` string and an optional `limit` integer (defaulting to `20`, minimum `1`, maximum `50`), the system shall return an HTTP 200 OK response containing a `KeysetPage` wrapper (`content`, `nextCursor`, `hasNext`, `size`) where `content` holds only the groups owned by that user (`id`, `name`, `isPersonal`, `creatorId`, `createdAt`, `updatedAt`), ordered deterministically by `is_personal DESC, created_at ASC, id ASC`.
- **GRP-REQ-07 (Ubiquitous):** The system shall guarantee that the authenticated user's default `"Personal"` group (`is_personal = true`) is always returned as the first element on the initial page of `GET /api/v1/groups`, regardless of its creation timestamp or whether the user has renamed it (`RF-06`, `FND-REQ-25`).
- **GRP-REQ-08 (Event-driven):** When the number of matching groups after the cursor position exceeds the requested `limit` in `GET /api/v1/groups`, the system shall return `hasNext = true`, `size = content.size()`, and a `nextCursor` token encoded via `KeysetCursorCodec` from the last returned group's `(isPersonal, createdAt, id)` tuple; when no additional groups remain, the system shall return `hasNext = false`, `nextCursor = null`, and `size = content.size()`.
- **GRP-REQ-09 (Unwanted event):** If a request to `GET /api/v1/groups` provides a blank, malformed, corrupted, checksum-invalid, non-group `cursor` token (such as a `TASK`, `TAG`, or `NOTIFICATION` cursor), or a `GROUP` cursor whose decoded `isPersonal`, `createdAt`, or `id` field is `null`, then the system shall reject the request with an HTTP 400 Bad Request status and an RFC 7807 problem detail in Spanish (`title: "Solicitud inválida"`, `detail: "El cursor de paginación proporcionado no es válido"`).
- **GRP-REQ-10 (Unwanted event):** If a request to `GET /api/v1/groups` provides a `limit` parameter less than `1` or greater than `50`, then the system shall reject the request with an HTTP 400 Bad Request status and an RFC 7807 problem detail in Spanish (`title: "Solicitud inválida"`, `detail: "El límite de paginación debe estar entre 1 y 50"`).
- **GRP-REQ-11 (Event-driven):** When an authenticated user requests a specific group by identifier via `GET /api/v1/groups/{groupId}`, the system shall verify that the group exists and belongs to the authenticated user, and return an HTTP 200 OK response with the group representation (`id`, `name`, `isPersonal`, `creatorId`, `createdAt`, `updatedAt`).
- **GRP-REQ-12 (Unwanted event):** If an authenticated user requests `GET /api/v1/groups/{groupId}` for a `groupId` that does not exist in the database (or has been deleted), then the system shall reject the request with an HTTP 404 Not Found status and an RFC 7807 problem detail in Spanish (`title: "Recurso no encontrado"`, `detail: "Grupo no encontrado"`, `FND-REQ-18`, `RNF-18`).
- **GRP-REQ-13 (Unwanted event):** If an authenticated user requests `GET /api/v1/groups/{groupId}` for an existing group that belongs to another user (`creator_id != authenticatedUserId`), then the system shall reject the request with an HTTP 403 Forbidden status and an RFC 7807 problem detail in Spanish (`title: "Acceso denegado"`, `detail: "No tiene permisos para realizar esta acción"`, `FND-REQ-19`, `RNF-07`).

### 3.3 Renombrado de Grupos (RF-06, RF-07, Apéndice B, FND-REQ-18, FND-REQ-19)

- **GRP-REQ-14 (Event-driven):** When an authenticated user submits a rename request to `PATCH /api/v1/groups/{groupId}` with a valid `name` (3 to 30 characters after `trim()`, ignoring any unknown or server-managed JSON properties such as `isPersonal`) for a group they own —including their `"Personal"` group (`is_personal = true`)—, the system shall update the group's `name` and `updated_at` UTC timestamp while preserving `is_personal` unchanged, and return an HTTP 200 OK response with the updated group representation (`id`, `name`, `isPersonal`, `creatorId`, `createdAt`, `updatedAt`).
- **GRP-REQ-15 (Unwanted event):** If a rename request to `PATCH /api/v1/groups/{groupId}` provides a missing, blank, or out-of-bounds `name` (fewer than 3 or more than 30 characters after trimming), then the system shall reject the request with an HTTP 400 Bad Request status and an RFC 7807 problem detail in Spanish (`title: "Error de validación"`, `detail: "Uno o más campos contienen errores de validación"`) including an `invalidParams` list with the validation error for `"name"`.
- **GRP-REQ-16 (Unwanted event):** If an authenticated user submits a rename request to `PATCH /api/v1/groups/{groupId}` for a `groupId` that does not exist in the database (or has been deleted), then the system shall reject the request with an HTTP 404 Not Found status and an RFC 7807 problem detail in Spanish (`title: "Recurso no encontrado"`, `detail: "Grupo no encontrado"`, `FND-REQ-18`).
- **GRP-REQ-17 (Unwanted event):** If an authenticated user submits a rename request to `PATCH /api/v1/groups/{groupId}` for an existing group that belongs to another user (`creator_id != authenticatedUserId`), then the system shall reject the request with an HTTP 403 Forbidden status and an RFC 7807 problem detail in Spanish (`title: "Acceso denegado"`, `detail: "No tiene permisos para realizar esta acción"`, `FND-REQ-19`).

### 3.4 Eliminación de Grupos e Inmutabilidad del Grupo "Personal" (RF-06, RF-07, FND-REQ-04, FND-REQ-06, FND-REQ-18, FND-REQ-19, FND-REQ-20)

- **GRP-REQ-18 (Unwanted event):** If an authenticated user attempts to delete their own default `"Personal"` group (`is_personal = true`) via `DELETE /api/v1/groups/{groupId}`, then the system shall reject the request with an HTTP 409 Conflict status and an RFC 7807 problem detail in Spanish (`title: "Conflicto de estado"`, `detail: "El grupo Personal no puede ser eliminado ni convertido en compartido"`), leaving the group and all its associated data intact.
- **GRP-REQ-19 (Event-driven):** When an authenticated user requests the deletion of a non-personal group (`is_personal = false`) they own via `DELETE /api/v1/groups/{groupId}`, the system shall acquire a pessimistic write lock on the group record, transactionally cancel and delete all pending scheduled jobs (`task_instance = 'task-due-' || task.id`) in `scheduled_tasks` for tasks belonging to that group, delete the group record cascading deletion to all its tasks (`tasks`), tags (`tags`), task-tag associations (`task_tags`), and memberships (`group_memberships`), and return an HTTP 204 No Content response.
- **GRP-REQ-20 (Unwanted event):** If an authenticated user requests `DELETE /api/v1/groups/{groupId}` for a `groupId` that does not exist in the database (or has been deleted), then the system shall reject the request with an HTTP 404 Not Found status and an RFC 7807 problem detail in Spanish (`title: "Recurso no encontrado"`, `detail: "Grupo no encontrado"`, `FND-REQ-18`).
- **GRP-REQ-21 (Unwanted event):** If an authenticated user requests `DELETE /api/v1/groups/{groupId}` for an existing group that belongs to another user (`creator_id != authenticatedUserId`), then the system shall reject the request with an HTTP 403 Forbidden status and an RFC 7807 problem detail in Spanish (`title: "Acceso denegado"`, `detail: "No tiene permisos para realizar esta acción"`, `FND-REQ-19`), evaluating authorization prior to checking `is_personal`.

### 3.5 Seguridad, CSRF, Formato de Entradas y Contratos de Error (RNF-07, RNF-14, FND-REQ-11, FND-REQ-14 a FND-REQ-17)

- **GRP-REQ-22 (Ubiquitous):** The system shall format all error responses produced by `/api/v1/groups/**` endpoints as RFC 7807 `ProblemDetail` objects with all human-readable descriptions (`title`, `detail`, and `invalidParams` reasons) written strictly in Spanish (`RNF-14`).
- **GRP-REQ-23 (Unwanted event):** If an unauthenticated client sends a request to any `/api/v1/groups/**` endpoint (`POST`, `GET`, `PATCH`, `DELETE`), then the system shall reject the request with an HTTP 401 Unauthorized status and an RFC 7807 problem detail in Spanish (`title: "No autenticado"`, `detail: "Debe iniciar sesión para acceder a este recurso"`).
- **GRP-REQ-24 (Unwanted event):** If an authenticated client sends a state-mutating request (`POST /api/v1/groups`, `PATCH /api/v1/groups/{groupId}`, or `DELETE /api/v1/groups/{groupId}`) without a valid anti-CSRF token, then the system shall reject the request with an HTTP 403 Forbidden status and an RFC 7807 problem detail in Spanish (`title: "Acceso denegado"`, `detail: "Token CSRF ausente o inválido para la solicitud."`, `FND-REQ-11`).
- **GRP-REQ-25 (Unwanted event):** If a client sends a request to `/api/v1/groups/**` with a non-numeric `{groupId}` path parameter, a non-integer `limit` query parameter, or a missing/malformed JSON request body on `POST` or `PATCH`, then the system shall reject the request with an HTTP 400 Bad Request status and an RFC 7807 problem detail in Spanish (`title: "Solicitud inválida"`, `detail: "El cuerpo o parámetro de la solicitud no tiene un formato válido"`).

---

## 4. Casos Borde y Condiciones de Fallo (Edge Cases)

- **EC-01: Normalización de espacios externos en el nombre del grupo (`trim`):** Si el usuario envía un nombre con espacios en blanco al inicio o al final (ej. `"  Universidad  "`), el sistema recorta los espacios externos antes de validar la longitud (3 a 30 caracteres) y de persistir el valor (`"Universidad"`). Si tras aplicar `trim()` la cadena resultante tiene menos de 3 caracteres (ej. `"  ab  "` o `"     "`), la solicitud es rechazada con `HTTP 400 Bad Request`, `title: "Error de validación"`, `detail: "Uno o más campos contienen errores de validación"` e `invalidParams` para `"name"` (`GRP-REQ-04`, `GRP-REQ-05`, `GRP-REQ-15`).
- **EC-02: Renombrado del grupo `"Personal"` y creación de otro grupo llamado `"Personal"`:** Si el usuario renombra su grupo por defecto `"Personal"` (`is_personal = true`) a otro nombre (ej. `"Bandeja Principal"`) y posteriormente crea un grupo individual llamado `"Personal"` (`is_personal = false`), el grupo por defecto original conserva su indicador `is_personal = true`, sigue apareciendo primero en el listado paginado y continúa protegido contra eliminación con `HTTP 409 Conflict`, mientras que el nuevo grupo individual con nombre `"Personal"` tiene `is_personal = false` y puede eliminarse normalmente (`RF-06`, `RF-07`, `FND-REQ-04`).
- **EC-03: Nombres de grupo duplicados para un mismo usuario:** Dado que ni el SRS (`RF-06`, Apéndice B) ni `V1__init_schema.sql` restringen nombres repetidos entre grupos de un mismo usuario, el sistema permite crear o renombrar grupos individuales con nombres idénticos, manteniendo un orden determinista en la paginación mediante `(is_personal DESC, created_at ASC, id ASC)`.
- **EC-04: Renombrado con el mismo nombre actual (idempotencia en `PATCH`):** Si el usuario envía `PATCH /api/v1/groups/{groupId}` con un nombre que, tras `trim()`, es idéntico al nombre actual del grupo, el sistema responde `HTTP 200 OK` con la representación válida del grupo sin producir errores de conflicto.
- **EC-05: Precedencia de existencia (`404`) y autorización (`403`) sobre reglas de estado (`409`):** Cuando el Usuario A intenta consultar (`GET`), renombrar (`PATCH`) o eliminar (`DELETE`) un `{groupId}`, el sistema primero verifica si el grupo existe en la base de datos (retornando `HTTP 404 Not Found` si no existe, `FND-REQ-18`) y acto seguido verifica si `creator_id == authenticatedUserId` (retornando `HTTP 403 Forbidden` con `title: "Acceso denegado"` y `detail: "No tiene permisos para realizar esta acción"` si pertenece al Usuario B, `FND-REQ-19`). Esta verificación de autorización precede estrictamente a la evaluación de `is_personal`, por lo que intentar eliminar el grupo `"Personal"` del Usuario B devuelve `403 Forbidden` en lugar de `409 Conflict`, sin filtrar si el grupo ajeno es personal.
- **EC-06: Eliminación de grupo con tareas que poseen avisos programados en `scheduled_tasks`:** Cuando se elimina un grupo individual que contiene tareas con trabajos `task-due-{taskId}` pendientes en la tabla `scheduled_tasks` de `db-scheduler`, el sistema elimina primero dichos registros de `scheduled_tasks` dentro de la misma transacción antes de borrar el grupo en cascada, garantizando que no queden trabajos huérfanos en el planificador (`RF-07`, `FND-REQ-06`).
- **EC-07: Desempate determinista en paginación Keyset para grupos con idéntico `created_at`:** Cuando varios grupos individuales de un mismo usuario comparten exactamente el mismo instante `created_at`, el predicado compuesto del cursor Keyset `(is_personal, created_at, id)` desempata por `id ASC`, asegurando que ninguna página omita ni repita registros (`FND-REQ-22`, `FND-REQ-25`).
- **EC-08: Cursor de otro tipo de entidad, manipulado o con campos nulos en `GET /api/v1/groups`:** Si un cliente envía como parámetro `cursor` un token codificado perteneciente a otra colección (`TaskCursor`, `TagCursor`, `NotificationCursor`), un token cuyos bytes fueron alterados (fallo de CRC32 o JSON inválido), o un `GroupCursor` con `isPersonal == null`, `createdAt == null` o `id == null`, el decodificador rechaza el valor y el endpoint retorna `HTTP 400 Bad Request` con `title: "Solicitud inválida"` y `detail: "El cursor de paginación proporcionado no es válido"` (`GRP-REQ-09`).
- **EC-09: Cursor sintácticamente válido que apunta a un grupo eliminado, más allá del final de la lista o a una tupla de otro usuario:** Dado que `GroupCursor(isPersonal, createdAt, id)` solo codifica valores de posición de ordenamiento y la consulta de `GET /api/v1/groups` filtra siempre por `creator_id = authenticatedUserId`, si el cursor recibido tiene formato e integridad válidos pero su posición corresponde a un grupo ya eliminado, supera al último grupo del usuario autenticado o proviene de una tupla de otro usuario, la consulta evalúa el predicado Keyset exclusivamente sobre los grupos del usuario autenticado (sin exponer datos ajenos) y, de no haber elementos posteriores, responde `HTTP 200 OK` con una página vacía (`content: []`, `nextCursor: null`, `hasNext: false`, `size: 0`).
- **EC-10: Parámetros de ruta/consulta con tipo inválido o cuerpo JSON malformado:** Si el cliente invoca `/api/v1/groups/abc` (`{groupId}` no numérico), `GET /api/v1/groups?limit=xyz` (`limit` no entero) o envía un cuerpo JSON vacío o sintácticamente inválido en `POST` o `PATCH`, `GlobalExceptionHandler` captura la excepción de enlace/lectura y retorna `HTTP 400 Bad Request` con `title: "Solicitud inválida"` y `detail: "El cuerpo o parámetro de la solicitud no tiene un formato válido"` (`GRP-REQ-25`).
- **EC-11: Inyección de `isPersonal` o propiedades desconocidas en payloads de `POST` y `PATCH`:** Los DTOs de entrada de creación y renombrado únicamente declaran el campo `name`. Si un cliente incluye propiedades adicionales en el JSON (como `"isPersonal": true` en `POST` o `"isPersonal": false` en `PATCH`), dichas propiedades son ignoradas durante la deserialización, garantizando que `POST` siempre cree grupos con `is_personal = false` y `PATCH` jamás altere el estado `is_personal` del grupo (`GRP-REQ-01`, `GRP-REQ-14`).
- **EC-12: Concurrencia en renombrado (`PATCH`) y eliminación (`DELETE`) de grupos (`RNF-17`):**
  - Ante dos solicitudes `PATCH /api/v1/groups/{groupId}` simultáneas sobre el mismo grupo, aplica la regla de consistencia del SRS (`RNF-17`): gana el *último cambio guardado*.
  - Al ejecutar `DELETE /api/v1/groups/{groupId}`, el sistema adquiere un bloqueo pesimista de escritura (`SELECT ... FOR UPDATE`) sobre el registro en `groups` antes de eliminar los trabajos `task-due-{taskId}` en `scheduled_tasks` y borrar el grupo en cascada, serializando cualquier intento concurrente de lectura/modificación o programación de tareas en ese grupo y evitando trabajos huérfanos en `scheduled_tasks`.

---

## 5. Matriz de Trazabilidad

| Requisito EARS | Requisito SRS / Constitución | Criterio de Aceptación Asociado |
| :--- | :--- | :--- |
| **GRP-REQ-01** | RF-06, Apéndice B | Creación de grupo individual en `POST /api/v1/groups` con `is_personal = false`, `creator_id = userId`, `trim()` en nombre y retorno `HTTP 201 Created` con `id`, `name`, `isPersonal`, `creatorId`, `createdAt`, `updatedAt`. |
| **GRP-REQ-02** | RNF-20, RF-06, FND-REQ-02 | Inserción atómica de membresía `role = 'CREATOR'` en `group_memberships` al crear el grupo. |
| **GRP-REQ-03** | RF-06, Apéndice B | Creación sin límite de cantidad de grupos individuales por usuario y sin restricción artificial de nombres repetidos. |
| **GRP-REQ-04** | RF-06, RF-07, Apéndice B, RNF-19 | Validación de longitud de `name` entre 3 y 30 caracteres tras `trim()` en creación y renombrado. |
| **GRP-REQ-05** | RNF-07, RNF-19, Apéndice B, FND-REQ-17 | Rechazo `HTTP 400 Bad Request` RFC 7807 (`title: "Error de validación"`, `invalidParams`) ante nombres vacíos o fuera de rango (3-30 chars) en `POST /api/v1/groups`. |
| **GRP-REQ-06** | RF-06, RF-24, RNF-04, FND-REQ-22 | Listado paginado Keyset en `GET /api/v1/groups` ordenado por `(is_personal DESC, created_at ASC, id ASC)` retornando `KeysetPage` (`content`, `nextCursor`, `hasNext`, `size`). |
| **GRP-REQ-07** | RF-06, RF-24, FND-REQ-25 | El grupo `"Personal"` (`is_personal = true`) aparece siempre en la primera posición de la primera página del usuario. |
| **GRP-REQ-08** | RNF-04, FND-REQ-23, FND-REQ-25 | Generación de `nextCursor` opaco con `KeysetCursor.GroupCursor`, `hasNext` y `size` precisos cuando existen o no más páginas. |
| **GRP-REQ-09** | RNF-04, RNF-07, FND-REQ-23 | Rechazo `HTTP 400 Bad Request` RFC 7807 (`title: "Solicitud inválida"`, `detail: "El cursor de paginación proporcionado no es válido"`) ante cursores manipulados, de otro tipo o con campos nulos. |
| **GRP-REQ-10** | RNF-04, RNF-07 | Rechazo `HTTP 400 Bad Request` RFC 7807 (`title: "Solicitud inválida"`, `detail: "El límite de paginación debe estar entre 1 y 50"`) cuando `limit` está fuera de `1..50`. |
| **GRP-REQ-11** | RF-06, RF-24 | Consulta de detalle de un grupo del usuario en `GET /api/v1/groups/{groupId}` retornando `HTTP 200 OK` con todos los campos del grupo. |
| **GRP-REQ-12** | RNF-18, FND-REQ-18 | Retorno `HTTP 404 Not Found` (`title: "Recurso no encontrado"`, `detail: "Grupo no encontrado"`) al consultar un `groupId` inexistente. |
| **GRP-REQ-13** | RNF-07, FND-REQ-19 | Retorno `HTTP 403 Forbidden` (`title: "Acceso denegado"`, `detail: "No tiene permisos para realizar esta acción"`) al consultar un grupo de otro usuario. |
| **GRP-REQ-14** | RF-06, RF-07, Apéndice B | Renombrado en `PATCH /api/v1/groups/{groupId}` para grupos individuales y el grupo `"Personal"`, preservando `is_personal` y actualizando `updated_at`. |
| **GRP-REQ-15** | RF-07, RNF-07, Apéndice B, FND-REQ-17 | Rechazo `HTTP 400 Bad Request` RFC 7807 (`title: "Error de validación"`, `invalidParams`) al renombrar un grupo con menos de 3 o más de 30 caracteres tras `trim()`. |
| **GRP-REQ-16** | RF-07, RNF-18, FND-REQ-18 | Retorno `HTTP 404 Not Found` al intentar renombrar un `groupId` inexistente. |
| **GRP-REQ-17** | RF-07, RNF-07, FND-REQ-19 | Retorno `HTTP 403 Forbidden` al intentar renombrar un grupo perteneciente a otro usuario. |
| **GRP-REQ-18** | RF-06, RF-07, FND-REQ-04, FND-REQ-20 | Rechazo `HTTP 409 Conflict` (`title: "Conflicto de estado"`, `detail: "El grupo Personal no puede ser eliminado ni convertido en compartido"`) al intentar eliminar el propio grupo `"Personal"`. |
| **GRP-REQ-19** | RF-07, RNF-17, FND-REQ-06, FND-REQ-31 | Eliminación en `DELETE /api/v1/groups/{groupId}` con bloqueo pesimista, limpieza transaccional de `scheduled_tasks` (`task-due-{taskId}`) y cascada completa (`HTTP 204 No Content`). |
| **GRP-REQ-20** | RF-07, RNF-18, FND-REQ-18 | Retorno `HTTP 404 Not Found` al intentar eliminar un `groupId` inexistente. |
| **GRP-REQ-21** | RF-07, RNF-07, FND-REQ-19 | Retorno `HTTP 403 Forbidden` al intentar eliminar un grupo perteneciente a otro usuario (evaluado antes de `is_personal`). |
| **GRP-REQ-22** | RNF-07, RNF-14, FND-REQ-14, FND-REQ-15 | Todas las respuestas de error siguen RFC 7807 (`ProblemDetail`) íntegramente en español. |
| **GRP-REQ-23** | RNF-07, FND-REQ-16 | Retorno `HTTP 401 Unauthorized` RFC 7807 en español ante peticiones sin sesión activa en `/api/v1/groups/**`. |
| **GRP-REQ-24** | RNF-07, FND-REQ-11 | Retorno `HTTP 403 Forbidden` RFC 7807 en español ante peticiones mutables (`POST`, `PATCH`, `DELETE`) sin token CSRF válido. |
| **GRP-REQ-25** | RNF-07, FND-REQ-17 | Retorno `HTTP 400 Bad Request` (`title: "Solicitud inválida"`, `detail: "El cuerpo o parámetro de la solicitud no tiene un formato válido"`) ante parámetros o JSON malformados. |
