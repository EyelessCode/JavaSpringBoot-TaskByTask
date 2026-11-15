# Especificación de Requisitos: 001-auth-and-users (Autenticación y Gestión de Usuarios)

**Estado:** approved  
**Fecha:** 2026-11-10 (Aprobada por el usuario)  
**Autor:** SDD Planner Agent  
**Referencias:**
- `docs/constitution.md` (Principios 1, 2, 4, 5, 6)
- `docs/TaskByTask-SRS.md` (RF-01, RF-02, RF-03, RF-04, RF-05; RNF-05, RNF-06, RNF-07, RNF-08, RNF-09, RNF-10, RNF-14, RNF-18, RNF-19, RNF-20; Apéndice B)
- `docs/TaskByTask-SVD.md` (CP-01, Sección 3.1)
- `docs/specs/000-foundation/spec.md` (Base arquitectural, modelo relacional e infraestructura de seguridad y errores)
- `AGENTS.md` (Reglas de dominio, convenciones y mitigación de escollos)
- `MEMORY.md` (Decisiones arquitecturales y estado del proyecto)

---

## 1. Propósito y Justificación (Why)

El módulo **001-auth-and-users** constituye el núcleo de identidad, control de acceso y gestión del ciclo de vida de usuarios de **Task by Task**. Proporciona a los usuarios una vía autónoma, segura y privada para:
1. Crear una cuenta con su perfil personal y zona horaria.
2. Iniciar y cerrar sesiones persistentes de manera confiable.
3. Proteger y recuperar el acceso a su cuenta mediante un conjunto exclusivo de códigos de recuperación de un solo uso, prescindiendo deliberadamente de dependencias de correo electrónico en la fase MVP.
4. Administrar sus datos personales, credenciales y preferencias horarias.
5. Ejercer el derecho a la supresión total y definitiva de su cuenta y sus datos personales.

En consonancia con la arquitectura de costo $0 y defensa en profundidad (`RNF-05`, `RNF-06`, `RNF-09`, `RNF-20`), este módulo asegura que:
- Las credenciales y códigos de recuperación nunca residan en texto plano en la capa de persistencia.
- Los intentos de autenticación y recuperación estén protegidos contra ataques automatizados de fuerza bruta y análisis por canales laterales de tiempo (*timing side-channel attacks*).
- Los errores nunca revelen la existencia de cuentas a terceros no autorizados.
- La identidad del usuario en sesiones persistentes esté desacoplada de atributos mutables (utilizando el identificador numérico inmutable como `PRINCIPAL_NAME`).
- La creación de la cuenta prepare de forma transparente e inmutable el espacio de trabajo inicial del usuario (el grupo `"Personal"` y sus membresías extensibles).

---

## 2. Alcance (Scope)

### 2.1 En Alcance (Fase 1 / MVP)
- **Registro de usuario (`RF-01` via `POST /api/v1/auth/register`):** Captura y validación de correo electrónico, nombre de usuario (sin distinción de mayúsculas/minúsculas), nombre para mostrar, contraseña en texto plano (6-30 caracteres) y zona horaria del cliente. Creación atómica de la cuenta, inicialización del grupo `"Personal"` (`is_personal = true`) con membresía creador, asignación de formato de hora por defecto `24H`, generación de 8 códigos de recuperación de un solo uso en mayúsculas (`^[A-Z0-9]{10}$`) entregados **una sola vez**, e inicio de sesión persistente automático.
- **Inicio y cierre de sesión (`RF-02`):**
  - Inicio de sesión (`POST /api/v1/auth/login`) con correo electrónico y contraseña, rate limiting defensivo particionado por cliente, validación estricta y actualización opcional de zona horaria IANA, establecimiento de sesión persistente indexada por ID inmutable de usuario (hasta 7 días en cookie segura).
  - Cierre de sesión (`POST /api/v1/auth/logout`) con invalidación inmediata de la sesión en base de datos y eliminación opcional de la suscripción push del dispositivo solicitante si se envía su endpoint, desacoplado del despacho VAPID de `004-notifications`.
- **Recuperación de contraseña con códigos de un solo uso (`RF-03` via `POST /api/v1/auth/recover-password`):** Restablecimiento de contraseña mediante correo, código de recuperación (normalizado en mayúsculas y espacios recortados) y nueva contraseña (6-30 caracteres). Consumo atómico del código, actualización de credenciales, mitigación de canales laterales de tiempo mediante verificación simulada (*dummy hash check*), e invalidación de absolutamente todas las sesiones activas del usuario en la base de datos.
- **Perfil y preferencias (`RF-04`):**
  - Consulta de perfil (`GET /api/v1/users/me`) con datos del usuario y conteo de códigos de recuperación restantes de 0 a 8 (sin exponer valores ni hashes).
  - Actualización parcial (`PATCH /api/v1/users/me`) de datos personales (`display_name`, `username`, `email`, `timezone`, `time_format` restringido a `24H`/`12H`), preservando la integridad del índice de sesiones mediante el ID inmutable.
  - Cambio de contraseña (`POST /api/v1/users/me/password`) exigiendo la contraseña actual, actualizando el hash e invalidando todas las demás sesiones activas del usuario mientras se preserva la sesión activa actual.
  - Regeneración de códigos de recuperación (`POST /api/v1/users/me/recovery-codes/regenerate`) exigiendo la contraseña actual, ejecutando eliminación física (`DELETE`) de los códigos previos e insertando 8 nuevos códigos entregados una sola vez.
- **Eliminación definitiva de cuenta (`RF-05` via `DELETE /api/v1/users/me`):** Solicitud destructiva que exige la contraseña actual del usuario, eliminando en cascada la cuenta, sus grupos, tareas, etiquetas, membresías, códigos de recuperación, suscripciones push, sesiones persistentes y cancelando cualquier trabajo programado pendiente en el planificador.
- **Respuestas de error uniformes en español (RFC 7807):** Emisión estandarizada de `ProblemDetail` en español para todos los códigos de estado HTTP aplicables (`400`, `401`, `403`, `404`, `409`, `429`, `500`).

### 2.2 Fuera de Alcance (Diferido a especificaciones funcionales posteriores)
- Verificación de correo electrónico mediante enlace o código OTP (`RF-11`, Fase Posterior).
- Recuperación de contraseña asistida por correo electrónico (`RF-11`, Fase Posterior).
- Cuenta e inicio de sesión independiente del rol Analista para el panel de métricas (`RF-31`, `RF-32`, Fase Posterior).
- Gestión de grupos adicionales (creación, edición, eliminación de grupos no personales) — corresponde a `002-groups`.
- Operaciones CRUD sobre tareas y etiquetas — corresponde a `003-tasks-and-tags`.
- Transmisión de avisos Web Push VAPID y centro de notificaciones dentro de la app — corresponde a `004-notifications`.

---

## 3. Requisitos del Sistema (EARS Format)

### 3.1 Registro de Usuario y Generación de Códigos (RF-01, Apéndice B)

- **AUTH-REQ-01 (Event-driven):** When a guest user submits a registration request to `POST /api/v1/auth/register` containing email, username, display name, password, and optional timezone, the system shall create a new user account, persist the password using an adaptive one-way cryptographic hash (`{bcrypt}`), store the timezone (defaulting to `UTC` if omitted), and assign `24H` as the default time format.
- **AUTH-REQ-02 (Ubiquitous):** The system shall enforce registration field boundaries according to SRS Appendix B on `POST /api/v1/auth/register`:
  - Email: non-blank, valid email syntax, maximum 255 characters, case-insensitive uniqueness (`LOWER(email)`).
  - Username: 3 to 30 characters, strictly alphanumeric (`^[A-Za-z0-9]{3,30}$`), case-insensitive uniqueness (`LOWER(username)`).
  - Display name: 2 to 50 characters.
  - Password: 6 to 30 characters in plaintext.
  - Timezone: valid IANA Time Zone identifier (`ZoneId`).
- **AUTH-REQ-03 (Unwanted event):** If a registration request to `POST /api/v1/auth/register` contains an email or username that is already registered (evaluated case-insensitively), then the system shall reject the request with an HTTP 409 Conflict status and an RFC 7807 problem detail in Spanish indicating the duplicated attribute.
- **AUTH-REQ-04 (Unwanted event):** If a registration request to `POST /api/v1/auth/register` contains invalid, malformed, or missing required fields, then the system shall reject the request with an HTTP 400 Bad Request status and an RFC 7807 problem detail containing the list of specific field validation errors in Spanish.
- **AUTH-REQ-05 (Event-driven):** When creating the user account, the system shall atomically create the default `"Personal"` group for that user (`name = 'Personal'`, `is_personal = true`, `creator_id = user.id`) and register the user as creator in the group memberships table (`role = 'CREATOR'`), satisfying `RNF-20`.
- **AUTH-REQ-06 (Event-driven):** When creating the user account, the system shall generate exactly 8 unique cryptographically secure single-use recovery codes formatted as 10-character uppercase alphanumeric strings (`^[A-Z0-9]{10}$`), persist their one-way hashes (`{bcrypt}`) in `recovery_codes` with `used = false`, and return the 8 plaintext codes in the registration response with an explicit notice that they are displayed only once.
- **AUTH-REQ-07 (Event-driven):** Upon successful user registration via `POST /api/v1/auth/register`, the system shall automatically create an authenticated persistent session valid for up to 7 days storing `String.valueOf(user.id)` as `PRINCIPAL_NAME`, issue the session cookie (`SESSION`), and return an HTTP 201 Created response with the created user profile and the 8 plaintext recovery codes.

### 3.2 Inicio y Cierre de Sesión (RF-02, RNF-06, RNF-09)

- **AUTH-REQ-08 (Event-driven):** When a user submits a login request to `POST /api/v1/auth/login` with their registered email and matching password, the system shall authenticate the user, create a persistent session in `SPRING_SESSION` valid for up to 7 days recording `String.valueOf(user.id)` as `PRINCIPAL_NAME`, issue the `SESSION` cookie (`HttpOnly`, `SameSite=Lax`, `Secure`), and return an HTTP 200 OK response with the authenticated user profile (including the remaining recovery codes count).
- **AUTH-REQ-09 (Event-driven):** When an authenticated login request to `POST /api/v1/auth/login` includes an optional client timezone, the system shall validate that it is a recognized IANA `ZoneId` and update the user's persisted timezone accordingly.
- **AUTH-REQ-10 (Unwanted event):** If a login request to `POST /api/v1/auth/login` provides an invalid or unrecognized IANA timezone identifier, then the system shall reject the request with an HTTP 400 Bad Request status and an RFC 7807 problem detail in Spanish (`title: "Solicitud inválida"`, `detail: "La zona horaria proporcionada no es válida"`), without creating or establishing an authenticated session.
- **AUTH-REQ-11 (Unwanted event):** If a login request to `POST /api/v1/auth/login` provides an email that does not exist or a password that does not match the stored hash, then the system shall reject the request with an HTTP 401 Unauthorized status and a generic RFC 7807 problem detail (`title: "Credenciales inválidas"`, `detail: "El correo electrónico o la contraseña son incorrectos"`), without disclosing whether the email address exists in the system.
- **AUTH-REQ-12 (Event-driven):** When an authenticated user submits a logout request to `POST /api/v1/auth/logout` containing an optional `endpoint` string in the request body (or header), the system shall immediately destroy the active session record in `SPRING_SESSION`, remove the matching subscription from `push_subscriptions` for that user if provided (`DELETE FROM push_subscriptions WHERE user_id = :userId AND endpoint = :endpoint`), and clear the `SESSION` cookie in the HTTP response, returning an HTTP 204 No Content status.

### 3.3 Recuperación de Contraseña con Códigos de Un Solo Uso (RF-03, RNF-05)

- **AUTH-REQ-13 (Event-driven):** When a user submits a password recovery request to `POST /api/v1/auth/recover-password` containing email, recovery code, and new password (6 to 30 characters), the system shall normalize the code with `TRIM(UPPER(code))`, verify it against the user's unused code hashes, mark the matching code as consumed (`used = true`, `used_at = current_timestamp`), update the user's password with an adaptive one-way hash (`{bcrypt}`), invalidate all existing active sessions of that user in `SPRING_SESSION` (`PRINCIPAL_NAME = String.valueOf(user.id)`), and return an HTTP 200 OK success response.
- **AUTH-REQ-14 (Unwanted event):** If a password recovery request to `POST /api/v1/auth/recover-password` provides an email that does not exist in the system, then the system shall execute a simulated dummy hash comparison of computational cost identical to `{bcrypt}` to prevent timing side-channel attacks, and reject the request with an HTTP 400 Bad Request status and a generic RFC 7807 problem detail (`title: "Recuperación fallida"`, `detail: "El correo electrónico o el código de recuperación son incorrectos, o el código ya fue utilizado"`).
- **AUTH-REQ-15 (Unwanted event):** If a password recovery request to `POST /api/v1/auth/recover-password` provides an existing email but a recovery code that does not match any unused code of that account (or has already been consumed), then the system shall reject the request with an HTTP 400 Bad Request status and the identical generic RFC 7807 problem detail (`title: "Recuperación fallida"`, `detail: "El correo electrónico o el código de recuperación son incorrectos, o el código ya fue utilizado"`), without leaking code existence or prior usage.
- **AUTH-REQ-16 (Unwanted event):** If the new password provided in `POST /api/v1/auth/recover-password` fails length or format constraints (shorter than 6 or longer than 30 characters), then the system shall reject the request with an HTTP 400 Bad Request status and an RFC 7807 problem detail detailing the password validation error in Spanish.

### 3.4 Perfil y Preferencias de Usuario (RF-04, RNF-14)

- **AUTH-REQ-17 (Event-driven):** When an authenticated user requests their profile information via `GET /api/v1/users/me`, the system shall return an HTTP 200 OK response with the user's identifier, email, username, display name, timezone, time format preference (`24H` or `12H`), account creation timestamp, and the exact count of remaining unused recovery codes (integer from 0 to 8), strictly omitting recovery code hashes or plaintext values.
- **AUTH-REQ-18 (Event-driven):** When an authenticated user submits a partial profile update to `PATCH /api/v1/users/me` containing one or more modifiable attributes (`display_name`, `username`, `email`, `timezone`, `time_format`), the system shall validate the supplied fields according to registration constraints, update only the provided attributes, preserve the user's session identifier and `PRINCIPAL_NAME = String.valueOf(user.id)`, and return an HTTP 200 OK response with the updated user profile.
- **AUTH-REQ-19 (Unwanted event):** If a profile update to `PATCH /api/v1/users/me` supplies `time_format` with a value other than `'24H'` or `'12H'`, then the system shall reject the request with an HTTP 400 Bad Request status and an RFC 7807 problem detail in Spanish (`title: "Error de validación"`, `detail: "El formato de hora debe ser 24H o 12H"`).
- **AUTH-REQ-20 (Unwanted event):** If a profile update to `PATCH /api/v1/users/me` attempts to set an email or username that is already taken by another registered user (evaluated case-insensitively, excluding the current user's existing identifiers), then the system shall reject the request with an HTTP 409 Conflict status and an RFC 7807 problem detail in Spanish identifying the conflicting field.
- **AUTH-REQ-21 (Event-driven):** When an authenticated user submits a password change request to `POST /api/v1/users/me/password` providing their `current_password` and a `new_password` (6 to 30 characters), the system shall verify that `current_password` matches the stored hash, update the user's password with the new hashed value (`{bcrypt}`), invalidate all other active sessions of that user in `SPRING_SESSION` while preserving the current active session, and return an HTTP 200 OK success response.
- **AUTH-REQ-22 (Unwanted event):** If a password change request to `POST /api/v1/users/me/password` supplies an incorrect `current_password`, then the system shall reject the request with an HTTP 400 Bad Request status and an RFC 7807 problem detail (`title: "Contraseña incorrecta"`, `detail: "La contraseña actual ingresada es incorrecta"`).
- **AUTH-REQ-23 (Event-driven):** When an authenticated user requests recovery code regeneration via `POST /api/v1/users/me/recovery-codes/regenerate` providing their `current_password`, the system shall verify the password, physically delete (`DELETE`) all existing recovery codes for that user from `recovery_codes`, generate 8 new cryptographically secure uppercase alphanumeric recovery codes (`^[A-Z0-9]{10}$`), persist their hashes with `used = false`, and return an HTTP 200 OK response with the 8 new plaintext codes and a notice that they are shown only once.
- **AUTH-REQ-24 (Unwanted event):** If a recovery code regeneration request to `POST /api/v1/users/me/recovery-codes/regenerate` supplies an incorrect `current_password`, then the system shall reject the request with an HTTP 400 Bad Request status and an RFC 7807 problem detail (`title: "Contraseña incorrecta"`, `detail: "La contraseña actual ingresada es incorrecta"`).

### 3.5 Eliminación Definitiva de Cuenta (RF-05, RNF-10)

- **AUTH-REQ-25 (Event-driven):** When an authenticated user confirms account deletion via `DELETE /api/v1/users/me` providing their matching current password, the system shall verify the password, delete the user record, cascade deletion to all owned groups, tasks, tags, recovery codes, and push subscriptions, cancel any pending scheduled jobs (`task-due-{taskId}`) in the scheduler, invalidate and remove all active sessions of that user from `SPRING_SESSION`, and return an HTTP 204 No Content response.
- **AUTH-REQ-26 (Unwanted event):** If an account deletion request to `DELETE /api/v1/users/me` provides an incorrect current password, then the system shall reject the request with an HTTP 400 Bad Request status and an RFC 7807 problem detail (`title: "Contraseña incorrecta"`, `detail: "La contraseña ingresada es incorrecta"`), preserving the account and all associated data intact.

### 3.6 Control de Tasa de Peticiones y Mitigación de Fuerza Bruta (RNF-06)

- **AUTH-REQ-27 (Ubiquitous):** The system shall enforce rate limiting on authentication and password recovery endpoints (`POST /api/v1/auth/login`, `POST /api/v1/auth/recover-password`, `POST /api/v1/auth/register`) restricting clients to a maximum of 5 attempts per 1-minute window partitioned by client IP address.
- **AUTH-REQ-28 (Unwanted event):** If incoming requests to rate-limited authentication endpoints exceed the allowed threshold, then the system shall reject the request with an HTTP 429 Too Many Requests status, an RFC 7807 problem detail in Spanish, and a `Retry-After` HTTP header specifying the integer number of seconds to wait before retrying.

### 3.7 Contratos de Error y Localización (RNF-07, RNF-14, RNF-18, RNF-19)

- **AUTH-REQ-29 (Ubiquitous):** The system shall format all error responses according to RFC 7807 (`ProblemDetail`) with all human-readable descriptions (`title` and `detail`) delivered strictly in Spanish.
- **AUTH-REQ-30 (Unwanted event):** If an unauthenticated client attempts to access protected endpoints (`GET /api/v1/users/me`, `PATCH /api/v1/users/me`, `POST /api/v1/users/me/password`, `POST /api/v1/users/me/recovery-codes/regenerate`, `DELETE /api/v1/users/me`, `POST /api/v1/auth/logout`), then the system shall reject the request with an HTTP 401 Unauthorized status and an RFC 7807 problem detail (`title: "No autenticado"`, `detail: "Debe iniciar sesión para acceder a este recurso"`).

---

## 4. Casos Borde y Condiciones de Fallo (Edge Cases)

- **EC-01: Normalización de identificadores en registro y actualización:** Si un usuario se registra o actualiza con espacios en blanco o mayúsculas en su correo o nombre de usuario (ej. `"  User@Domain.COM  "` o `" User123 "`), el sistema recorta los espacios en blanco externos (`trim`) y evalúa la unicidad en minúsculas canónicas (`LOWER()`). El correo se persiste normalizado en minúsculas.
- **EC-02: Colisión de unicidad consigo mismo en actualización parcial (`PATCH`):** Si un usuario envía una solicitud a `PATCH /api/v1/users/me` donde el `email` o `username` coincide con su valor actual (o cambia únicamente mayúsculas/minúsculas sin alterar el valor canónico), el sistema no debe considerarlo como colisión y debe aceptar la actualización sin emitir un error 409 Conflict.
- **EC-03: Intento de reutilización de código de recuperación ya consumido:** Si un usuario intenta restablecer la contraseña utilizando un código de recuperación válido sintácticamente pero cuyo estado ya es `used = true`, la solicitud es rechazada con el mismo mensaje genérico que si el código fuera incorrecto (`title: "Recuperación fallida"`), evitando revelar si el código alguna vez existió o fue consumido (`RF-03`).
- **EC-04: Usuario con los 8 códigos de recuperación agotados:** Si un usuario consumió sus 8 códigos de recuperación y solicita recuperación de contraseña, la solicitud es rechazada de forma controlada sin fuga de información. En la fase MVP, al no existir verificación por correo electrónico, el usuario no podrá recuperar el acceso (`docs/TaskByTask-SRS.md` RF-03 y Apéndice D).
- **EC-05: Invocación concurrente con el mismo código de recuperación:** Si dos solicitudes a `POST /api/v1/auth/recover-password` llegan de manera concurrente intentando usar el mismo código no consumido, el sistema debe procesar la operación de manera atómica (mediante bloqueo pesimista o transacción serializable), garantizando que solo una solicitud tenga éxito y la otra falle inmediatamente.
- **EC-06: Política de invalidación de sesiones en cambio vs recuperación de contraseña:**
  - En recuperación de contraseña (`POST /api/v1/auth/recover-password`), se invalidan absolutamente todas las sesiones activas del usuario en `SPRING_SESSION` (`PRINCIPAL_NAME = String.valueOf(user.id)`), forzando re-autenticación en todos los dispositivos.
  - En cambio voluntario de contraseña desde el perfil (`POST /api/v1/users/me/password`), se invalidan todas las *demás* sesiones activas del usuario en la base de datos, preservando la sesión activa actual del dispositivo que ejecutó la operación para evitar fricción innecesaria.
- **EC-07: Inserción de zona horaria IANA no estándar o ficticia en registro o login:** Si un usuario envía un valor como `"Custom/Zone"` o `"UTC+5"` que no se encuentra registrado en el catálogo IANA oficial de Java (`ZoneId.of()`), el sistema rechaza el campo con `HTTP 400 Bad Request` indicando que la zona horaria no es válida, sin crear la cuenta (en registro) ni establecer la sesión (en login).
- **EC-08: Eliminación de cuenta con tareas con alertas pendientes en el scheduler:** Si el usuario solicita la eliminación definitiva de su cuenta vía `DELETE /api/v1/users/me` y tiene tareas pendientes con avisos programados en `scheduled_tasks`, el sistema debe cancelar todos los trabajos asociados (`task-due-{taskId}`) de forma transaccional, evitando ejecuciones huérfanas en el planificador.
- **EC-09: Desasociación selectiva de suscripciones push en logout:** Cuando el cliente envía `endpoint` en `POST /api/v1/auth/logout`, se elimina exclusivamente la fila coincidente en `push_subscriptions` para el usuario autenticado, preservando las suscripciones push registradas en otros dispositivos del mismo usuario. Si no se envía `endpoint`, el logout invalida la sesión HTTP sin alterar la tabla `push_subscriptions` (`RF-02`, `RF-27`).
- **EC-10: Petición de eliminación, cambio de clave o regeneración de códigos con contraseña actual errónea:** Si un atacante o usuario confuso intenta cambiar la contraseña, regenerar códigos o eliminar la cuenta ingresando una contraseña actual incorrecta, la acción se cancela en su totalidad con `HTTP 400 Bad Request` sin alterar ningún dato del usuario.
- **EC-11: Normalización de mayúsculas en códigos de recuperación:** Los códigos de recuperación generados son estrictamente de 10 caracteres alfanuméricos en mayúsculas (`^[A-Z0-9]{10}$`). Durante la recuperación en `POST /api/v1/auth/recover-password`, cualquier entrada del usuario es normalizada mediante `TRIM(UPPER(code))` antes de ser comparada contra los hashes `{bcrypt}` no usados, admitiendo códigos introducidos en minúsculas o con espacios accidentales.
- **EC-12: Canal lateral de tiempo en recuperación de contraseña:** Para evitar que un atacante determine si un correo electrónico está registrado analizando el tiempo de respuesta de `POST /api/v1/auth/recover-password`, ante un correo no registrado el sistema ejecuta una verificación simulada con BCrypt contra un hash estático predefinido, equiparando la latencia con la de una verificación real de código.

---

## 5. Matriz de Trazabilidad

| Requisito EARS | Requisito SRS / Constitución | Criterio de Aceptación Asociado |
| :--- | :--- | :--- |
| **AUTH-REQ-01** | RF-01, RNF-05, RNF-14 | Creación de cuenta en `POST /api/v1/auth/register` con hash `{bcrypt}`, zona horaria IANA y formato `24H`. |
| **AUTH-REQ-02** | Apéndice B, RF-01 | Validación estricta de longitudes y caracteres para email, username (3-30 alfanumérico), display name (2-50) y password (6-30). |
| **AUTH-REQ-03** | RF-01, Apéndice B | Rechazo con HTTP 409 Conflict ante colisión de `LOWER(email)` o `LOWER(username)`. |
| **AUTH-REQ-04** | RNF-07, Apéndice B | Respuestas HTTP 400 RFC 7807 con lista de errores por campo ante fallos de validación en registro. |
| **AUTH-REQ-05** | RF-01, RF-06, RNF-20 | Creación atómica del grupo `"Personal"` (`is_personal = true`) con membresía de creador al registrarse. |
| **AUTH-REQ-06** | RF-01, Apéndice B | Generación de 8 códigos de recuperación `^[A-Z0-9]{10}$`, almacenamiento de hashes `{bcrypt}` y entrega en plano una sola vez. |
| **AUTH-REQ-07** | RF-01, RF-02, RNF-09 | Inicio de sesión automático tras registro con cookie `SESSION` (7 días) y `PRINCIPAL_NAME = user.id`. |
| **AUTH-REQ-08** | RF-02, RNF-09 | Inicio de sesión en `POST /api/v1/auth/login` con correo y clave, emisión de cookie `SESSION` persistente. |
| **AUTH-REQ-09** | RF-02, RF-04, RNF-14 | Actualización de zona horaria IANA del usuario al iniciar sesión si se envía en el payload de login. |
| **AUTH-REQ-10** | RF-02, RNF-07 | Rechazo con HTTP 400 RFC 7807 ante zona horaria inválida en `POST /api/v1/auth/login` sin crear sesión. |
| **AUTH-REQ-11** | RF-02, RNF-07 | Error genérico HTTP 401 RFC 7807 ante credenciales incorrectas sin revelar existencia del correo en login. |
| **AUTH-REQ-12** | RF-02, RF-27 | Cierre de sesión en `POST /api/v1/auth/logout`, invalidación en BD y limpieza opcional de suscripción push por endpoint. |
| **AUTH-REQ-13** | RF-03, RNF-05, RNF-09 | Restablecimiento de clave en `POST /api/v1/auth/recover-password`, normalización `TRIM(UPPER())`, consumo de código e invalidación total de sesiones. |
| **AUTH-REQ-14** | RF-03, RNF-05, RNF-07 | Mitigación de timing attack en recuperación mediante verificación BCrypt simulada ante correo no registrado. |
| **AUTH-REQ-15** | RF-03, RNF-07 | Error genérico HTTP 400 ante código inválido/usado sin revelar existencia ni consumo previo. |
| **AUTH-REQ-16** | RF-03, Apéndice B | Validación de longitud (6-30 chars) para la nueva contraseña en `POST /api/v1/auth/recover-password`. |
| **AUTH-REQ-17** | RF-04, Apéndice B | Consulta de perfil en `GET /api/v1/users/me` con datos del usuario y conteo de códigos restantes (0-8) sin exponer hashes ni códigos. |
| **AUTH-REQ-18** | RF-04, Apéndice B | Actualización parcial en `PATCH /api/v1/users/me`, preservando `PRINCIPAL_NAME = user.id`. |
| **AUTH-REQ-19** | RF-04, Apéndice B | Validación estricta de `time_format` (`24H` o `12H`), rechazando otros valores con HTTP 400 Bad Request. |
| **AUTH-REQ-20** | RF-04, Apéndice B | Conflicto HTTP 409 al intentar actualizar a un email o username ya registrado por otro usuario. |
| **AUTH-REQ-21** | RF-04, RNF-05, RNF-09 | Cambio de contraseña en `POST /api/v1/users/me/password`, invalidando otras sesiones y preservando la activa. |
| **AUTH-REQ-22** | RF-04, RNF-07 | Rechazo con HTTP 400 ante contraseña actual incorrecta en cambio de contraseña. |
| **AUTH-REQ-23** | RF-04, Apéndice B | Regeneración de códigos en `POST /api/v1/users/me/recovery-codes/regenerate` con `DELETE` físico de anteriores y emisión de 8 nuevos `^[A-Z0-9]{10}$`. |
| **AUTH-REQ-24** | RF-04, RNF-07 | Rechazo con HTTP 400 ante contraseña actual incorrecta en regeneración de códigos. |
| **AUTH-REQ-25** | RF-05, RNF-10 | Eliminación de cuenta en `DELETE /api/v1/users/me` y cascada completa (grupos, tareas, etiquetas, sesiones, suscripciones y jobs del scheduler). |
| **AUTH-REQ-26** | RF-05, RNF-07 | Rechazo con HTTP 400 ante contraseña incorrecta al solicitar eliminación de cuenta. |
| **AUTH-REQ-27** | RNF-06 | Rate limiting con límite de 5 intentos por minuto particionado por IP para endpoints públicos de auth. |
| **AUTH-REQ-28** | RNF-06 | HTTP 429 Too Many Requests con `Retry-After` al exceder el umbral de intentos. |
| **AUTH-REQ-29** | RNF-07, RNF-14 | Respuestas de error estructuradas según RFC 7807 (`ProblemDetail`) redactadas en español. |
| **AUTH-REQ-30** | RNF-07 | HTTP 401 Unauthorized RFC 7807 al acceder a endpoints protegidos sin sesión activa. |
