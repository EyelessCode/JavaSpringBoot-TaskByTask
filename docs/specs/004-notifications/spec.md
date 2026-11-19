# Especificación de Requisitos: 004-notifications (Notificaciones Push Web, Jobs de Vencimiento y Centro de Notificaciones)

**Estado:** approved  
**Fecha:** 2026-11-15  
**Autor:** SDD Planner Agent  
**Referencias:**
- `docs/constitution.md` (Principios 1, 2, 3, 4, 5, 6)
- `docs/TaskByTask-SRS.md` (RF-02, RF-05, RF-12, RF-13, RF-27, RF-28, RF-29, RF-30; RNF-04, RNF-07, RNF-08, RNF-10, RNF-14, RNF-18, RNF-20; Apéndice B)
- `docs/TaskByTask-SVD.md` (OBJ-02, Riesgo 1, CP-06)
- `docs/specs/000-foundation/spec.md` (FND-REQ-01, FND-REQ-05, FND-REQ-06, FND-REQ-08, FND-REQ-14 a FND-REQ-25, FND-REQ-26 a FND-REQ-31)
- `docs/specs/001-auth-and-users/spec.md` (AUTH-REQ-12, AUTH-REQ-25, EC-09)
- `docs/specs/003-tasks-and-tags/spec.md` (TSK-REQ-11)
- `AGENTS.md` (Reglas de fechas/09:00 AM, extensibilidad MVP `RNF-20`, localización española RFC 7807)

---

## 1. Propósito y Justificación (Why)

El módulo `004-notifications` completa el ciclo funcional del MVP orientado a cumplimiento puntual de tareas (`OBJ-02`, `CP-06`) conectando la programación de vencimientos establecida por `003-tasks-and-tags` (`scheduled_tasks`) con la entrega de alertas al usuario:

1. **Gestión de Suscripciones Web Push y Clave VAPID (`RF-27`):** Expone de forma segura la clave pública VAPID del servidor y permite registrar, actualizar, re-vincular y dar de baja suscripciones de navegador multi-dispositivo sobre la tabla existente `push_subscriptions`, preservando la limpieza selectiva al cerrar sesión (`RF-02`, `AUTH-REQ-12`).
2. **Ejecución Persistente de Avisos de Vencimiento (`RF-28`, `RF-27`, `FND-REQ-30`):** Consume los jobs persistidos por `003-tasks-and-tags` en `db-scheduler` (`task-due-notification` / `task-due-{taskId}`), verifica la vigencia actual de la tarea, registra de forma inmutable la notificación in-app en `notifications` y realiza el envío best-effort cifrado RFC 8291 / firmado VAPID RFC 8292 hacia los dispositivos suscritos sin bloquear ni revertir el registro in-app ante fallos de entrega externa (`Riesgo 1` del SVD).
3. **Centro de Notificaciones In-App y Retención Automática (`RF-29`, `RNF-04`, `FND-REQ-08`):** Ofrece consulta paginada por cursor Keyset (`created_at DESC, id DESC`), conteo de no leídas, marcado individual y masivo como leídas, eliminación individual y purga automática recurrente mediante `db-scheduler` de registros con antigüedad superior a 1 mes (30 días calendario).

> **Compatibilidad de Esquema (`V1__init_schema.sql`):** Toda la funcionalidad definida en esta especificación opera directamente sobre las tablas existentes `push_subscriptions`, `notifications` y `scheduled_tasks` de `V1__init_schema.sql`, sin requerir nuevas migraciones SQL en Flyway.

---

## 2. Alcance (Scope)

### 2.1 En Alcance (MVP)
- **Configuración pública VAPID (`RF-27`):**
  - Endpoint autenticado `GET /api/v1/notifications/push/vapid-public-key` que informa si Web Push está habilitado y entrega la clave pública VAPID del servidor en formato Base64URL.
- **Ciclo de vida de suscripciones Web Push (`RF-27`, `RF-02`, `RNF-10`):**
  - Registro/actualización idempotente multi-dispositivo (`POST /api/v1/notifications/push/subscriptions`) con payload W3C `PushSubscription` (`endpoint`, `keys.p256dh`, `keys.auth`).
  - Re-vinculación atómica cuando un navegador compartido cambia de cuenta autenticada sin logout previo (respetando `endpoint UNIQUE`).
  - Desuscripción explícita por identificador (`DELETE /api/v1/notifications/push/subscriptions/{subscriptionId}`) o por `endpoint` (`DELETE /api/v1/notifications/push/subscriptions?endpoint=...`).
  - Compatibilidad íntegra con `POST /api/v1/auth/logout` (`endpoint` opcional, `001-auth-and-users`) y `DELETE /api/v1/users/me`.
- **Consumidor `db-scheduler` y entrega Web Push (`RF-28`, `RF-27`, `FND-REQ-30`, `FND-REQ-31`):**
  - Handler `db-scheduler` registrado para `("task-due-notification", "task-due-{taskId}")`.
  - Validación defensiva pre-ejecución (existencia de tarea, `completed == false`, `dueDate != null`, detección de reprogramación concurrente hacia el futuro).
  - Resolución de destinatario preparada para `RNF-20` (`assignedUser` si existiera; `group.creator` en grupos individuales del MVP).
  - Persistencia transaccional previa e independiente de la notificación in-app (`eventType = "TASK_DUE"`, `content` en español `<= 500` caracteres, `is_read = false`).
  - Despacho HTTP best-effort cifrado RFC 8291 (`aes128gcm`) y firmado RFC 8292 (VAPID `ES256`) a las suscripciones activas del destinatario, limpieza automática ante respuestas `404`/`410`, y degradación segura ante errores transitorios o entorno sin claves VAPID.
- **Centro de Notificaciones y Retención (`RF-29`, `RNF-04`, `FND-REQ-08`, `FND-REQ-25`):**
  - Listado Keyset paginado (`GET /api/v1/notifications`) con `NotificationCursor(createdAt, id)`, orden `created_at DESC, id DESC`, parámetro `limit` (`1..50`, defecto `20`) y filtro opcional `unreadOnly`.
  - Conteo de notificaciones no leídas (`GET /api/v1/notifications/unread-count`).
  - Marcado individual como leída (`PATCH /api/v1/notifications/{notificationId}/read`, idempotente).
  - Marcado masivo de todas las notificaciones propias como leídas (`PATCH /api/v1/notifications/read-all`).
  - Eliminación individual (`DELETE /api/v1/notifications/{notificationId}`).
  - Job recurrente en `db-scheduler` para purga automática diaria de notificaciones con antigüedad mayor a 1 mes (30 días calendario desde `created_at`).
- **Contratos de Seguridad, Propiedad y Errores (`RNF-07`, `RNF-08`, `RNF-14`, `RNF-18`):**
  - Autenticación de sesión (`401`), validación CSRF en mutaciones (`403`), precedencia estricta `404` -> `403` en recursos por ID, y mensajes `ProblemDetail` RFC 7807 en español.

### 2.2 Fuera de Alcance (Diferido a Fases Posteriores o Frontend)
- **Notificaciones de colaboración (`RF-30`):** Avisos por asignación/desasignación en grupos compartidos, invitaciones o cambios de rol en grupos (fase Posterior).
- **Recordatorios previos configurables (`RF-20`):** Múltiples alertas previas antes de la fecha/hora de vencimiento (fase Posterior).
- **Actualizaciones en tiempo real vía SSE (`RF-33`) o WebSockets:** Diferido a fase Posterior; el MVP combina Web Push en navegador y consulta bajo demanda del Centro de Notificaciones.
- **Recordatorio de permiso denegado cada 8 horas (`RF-27`):** El aviso descartable por 8 horas cuando el usuario rechaza permisos de notificación en el navegador es una responsabilidad exclusiva de la interfaz frontend, sin estado ni endpoints en el backend.
- **Notificaciones por correo electrónico transaccional o canales externos alternativos.**
- **Alteraciones al esquema de base de datos (`Flyway`):** No se crean ni modifican tablas en esta especificación.

---

## 3. Requisitos del Sistema (EARS Format)

### 3.1 Clave Pública VAPID y Suscripciones Web Push (`RF-27`, `RF-02`, `RNF-10`)

- **NTF-REQ-01 (Event-driven):** When an authenticated user requests `GET /api/v1/notifications/push/vapid-public-key`, the system shall return HTTP 200 OK with `{ "enabled": true, "publicKey": "<vapidPublicKey>" }` only when Web Push delivery is enabled (`enabled = true`) and all required VAPID properties (`publicKey`, `privateKey`, `subject` `mailto:`/`https:`) loaded from external configuration (`FND-REQ-34`, `FND-REQ-35`) form a valid matching P-256 EC key pair; whenever Web Push is disabled (`enabled = false`) or server VAPID configuration is partial (missing any property) or cryptographically malformed/mismatched, the system shall log a startup/diagnostic warning without exposing private key material, preserve normal application startup, and return HTTP 200 OK with `{ "enabled": false, "publicKey": null }`.
- **NTF-REQ-02 (Event-driven):** When an authenticated user sends `POST /api/v1/notifications/push/subscriptions` containing a valid W3C push subscription payload (`endpoint`, `keys.p256dh`, `keys.auth`) for an `endpoint` not yet present in `push_subscriptions`, the system shall trim `endpoint`, `keys.p256dh`, and `keys.auth`, ignore unknown W3C fields (such as `expirationTime`) and client-supplied server-managed fields (`id`, `userId`, `createdAt`), persist a new subscription owned by the authenticated user with server-assigned metadata, and return HTTP 201 Created with `PushSubscriptionResponse` (`id`, `endpoint`, `createdAt`), omitting cryptographic key material from the response body.
- **NTF-REQ-03 (Event-driven):** When an authenticated user sends `POST /api/v1/notifications/push/subscriptions` for a trimmed `endpoint` already registered to the same user, the system shall idempotently update `p256dh` and `auth` on the existing record without creating a duplicate row and return HTTP 200 OK with the updated `PushSubscriptionResponse`.
- **NTF-REQ-04 (Event-driven):** When an authenticated user sends `POST /api/v1/notifications/push/subscriptions` for a trimmed `endpoint` currently bound to a different user account (for example, on a shared browser after another user failed to log out), the system shall atomically rebind `user_id` to the currently authenticated user, update `p256dh`, `auth`, and `created_at`, and return HTTP 200 OK with `PushSubscriptionResponse`, ensuring the prior user account immediately ceases receiving push messages on that device.
- **NTF-REQ-05 (Ubiquitous):** The system shall allow each user to hold multiple distinct active `push_subscriptions` simultaneously across different browsers or devices (`RF-27`), governed by global uniqueness on `endpoint` (`endpoint TEXT NOT NULL UNIQUE`).
- **NTF-REQ-06 (Ubiquitous):** The system shall trim and validate incoming `POST /api/v1/notifications/push/subscriptions` payloads and reject invalid requests with HTTP 400 Bad Request (`title: "Error de validación"`, `detail: "Uno o más campos contienen errores de validación"` with field-level `invalidParams`) whenever:
  - `endpoint` (after trimming) is null/blank, exceeds 2048 characters, or is not a valid absolute `http` or `https` URI with host.
  - `keys` is null, or `keys.p256dh` or `keys.auth` (after trimming) is null/blank or exceeds 255 characters (`VARCHAR(255)`).
  - `keys.p256dh` does not decode as a valid Base64/Base64URL 65-byte uncompressed P-256 EC public key (`0x04` prefix and valid curve point), or `keys.auth` does not decode as a valid Base64/Base64URL 16-byte authentication secret per RFC 8291.
- **NTF-REQ-07 (Event-driven):** When an authenticated user requests `DELETE /api/v1/notifications/push/subscriptions/{subscriptionId}` for an existing subscription owned by that user, the system shall delete the subscription record and return HTTP 204 No Content.
- **NTF-REQ-08 (Unwanted event):** If an authenticated user requests `DELETE /api/v1/notifications/push/subscriptions/{subscriptionId}` for an ID that does not exist or belongs to another user, then the system shall enforce strict `404` -> `403` precedence and return a localized Spanish RFC 7807 `ProblemDetail` (HTTP 404 `"Suscripción push no encontrada"` if absent; HTTP 403 `"No tiene permisos para realizar esta acción"` if owned by another user).
- **NTF-REQ-09 (Event-driven):** When an authenticated user requests `DELETE /api/v1/notifications/push/subscriptions?endpoint={endpoint}` with a valid non-blank query parameter (normalized with `trim()`), the system shall delete any matching subscription owned by that user (`user_id` AND trimmed `endpoint`) and return HTTP 204 No Content idempotently (even if no matching subscription exists for that user, without revealing or modifying subscriptions owned by other users); if the `endpoint` query parameter is missing or blank after trimming, the system shall return HTTP 400 Bad Request with Spanish RFC 7807 `ProblemDetail` (`title: "Solicitud inválida"`, `detail: "El parámetro 'endpoint' es obligatorio y no puede estar vacío"`).
- **NTF-REQ-10 (Ubiquitous):** The system shall preserve full compatibility with `POST /api/v1/auth/logout` (`AUTH-REQ-12`, selective deletion by `userId` and optional `endpoint`) and `DELETE /api/v1/users/me` (`AUTH-REQ-25`, cascading deletion of all `push_subscriptions` and `notifications` plus cancellation of pending `scheduled_tasks`).

### 3.2 Ejecución de Jobs de Vencimiento y Entrega Web Push (`RF-28`, `RF-27`, `FND-REQ-30`, `FND-REQ-31`, `RNF-20`)

- **NTF-REQ-11 (Event-driven):** When `db-scheduler` invokes the registered handler for `task_name = "task-due-notification"` and `task_instance = "task-due-{taskId}"` (`task_data = NULL`), the system shall parse `taskId`, load the task together with its group, group creator, and nullable assigned user, and verify that the task exists, is incomplete (`completed == false`), and has a non-null `dueDate`.
- **NTF-REQ-12 (Event-driven):** When the `task_instance` identifier is malformed, the referenced task no longer exists, `task.completed == true`, or `task.dueDate == null`, the system shall exit the job handler cleanly (`no-op`) without persisting an in-app notification or dispatching Web Push requests, allowing `db-scheduler` to remove the finished job entry.
- **NTF-REQ-13 (State-driven):** While evaluating a valid incomplete task (`dueDate != null`), the system shall recalculate the due trigger instant using `DateTimeProvider.calculateDueTriggerInstant(task.getDueDate(), task.getDueTime(), recipientZone, now)` against the resolved recipient's IANA `ZoneId` (`FND-REQ-28`, `FND-REQ-30`); if a trigger `Instant` exists and is strictly after `now.plusSeconds(1)` (covering both future dates `dueDate > today(zone)`, date-only tasks for `dueDate == today(zone)` prior to `09:00:00` local time, and future `dueDate + dueTime`), the system shall reschedule `("task-due-notification", "task-due-{taskId}")` to that recalculated instant without emitting a premature notification.
- **NTF-REQ-14 (Ubiquitous):** The system shall resolve the due-notification recipient in accordance with `RNF-20`: targeting `task.assignedUser` when non-null (Posterior shared-group readiness), and `task.group.creator` otherwise (all MVP individual groups).
- **NTF-REQ-15 (Event-driven):** When a due-notification job passes validation checks, the system shall persist exactly one in-app notification record in `notifications` for the resolved recipient within an independent committed transaction before any external network call, setting `event_type = "TASK_DUE"`, `is_read = false`, `created_at = DateTimeProvider.now()` (UTC), and a localized Spanish `content` string formatted per `RNF-14` (`dd/MM/yyyy`) and the recipient's `time_format` (`RF-04`: `HH:mm` when `24H`, `h:mm a` with `AM`/`PM` when `12H`, and omitting any time component for date-only tasks without fabricating `00:00` or `09:00`):
  - Date + time: `La tarea "<title>" del grupo "<groupName>" vence el <dd/MM/yyyy> a las <horaFormateada>.`
  - Date-only: `La tarea "<title>" del grupo "<groupName>" vence el <dd/MM/yyyy>.`
  Normalizing line breaks into single spaces and truncating safely with `"..."` if needed so `content.length() <= 500` (`VARCHAR(500)`).
- **NTF-REQ-16 (Event-driven):** After committing the in-app notification, when Web Push delivery is active (valid VAPID configuration per `NTF-REQ-01`) and the recipient holds one or more rows in `push_subscriptions`, the system shall attempt best-effort delivery to every active subscription of that user by encrypting the Spanish JSON notification payload (`notificationId`, `eventType`, `title: "Tarea por vencer"`, `body` equal verbatim to `notifications.content`, `taskId`, `groupId`, `createdAt`) per RFC 8291 (`aes128gcm`) and signing the HTTP POST request with a VAPID JWT (`ES256`) per RFC 8292 (`Authorization: vapid t=..., k=...`, `TTL`).
- **NTF-REQ-17 (Unwanted event):** If a push gateway endpoint responds with HTTP `404 Not Found` or `410 Gone` during Web Push delivery, then the system shall delete that expired/revoked `push_subscriptions` record from the database, log the cleanup without exposing secrets or PII, and continue sending to any remaining subscriptions of the user.
- **NTF-REQ-18 (Unwanted event):** If Web Push delivery cannot proceed or fails due to transient HTTP errors (`429`, `5xx`), network/socket timeouts, client key encryption failure, disabled Web Push (`enabled = false`), or partial/malformed/mismatched server VAPID configuration (`NTF-REQ-01`), then the system shall log diagnostic context safely without exposing sensitive credentials or rolling back the persisted `notifications` row, continue delivering to any remaining valid subscriptions of that user, and return normally to `db-scheduler` so the job is not re-executed endlessly or duplicated.

### 3.3 Centro de Notificaciones y Retención Automática (`RF-29`, `RNF-04`, `FND-REQ-08`, `FND-REQ-25`)

- **NTF-REQ-19 (Event-driven):** When an authenticated user requests `GET /api/v1/notifications`, the system shall return HTTP 200 OK containing a `KeysetPage<NotificationResponse>` restricted strictly to `user_id = currentUserId` and ordered deterministically by `created_at DESC, id DESC` (newest first, tie-broken by primary ID descending), using opaque `NotificationCursor(createdAt, id)` pagination tokens (`FND-REQ-22`, `FND-REQ-23`, `FND-REQ-25`).
- **NTF-REQ-20 (Ubiquitous):** The system shall support query parameters and item shape on `GET /api/v1/notifications`:
  - `cursor` (optional): opaque cursor token produced by `nextCursor`; blank cursors (if provided), malformed Base64URL/checksum-invalid tokens, non-`NOTIFICATION` cursor types, or decoded `NotificationCursor` values containing `createdAt == null` or `id == null` shall be rejected with HTTP 400 Bad Request (`title: "Solicitud inválida"`, `detail: "El cursor de paginación proporcionado no es válido"`).
  - `limit` (optional, default `20`): integer between `1` and `50` inclusive; values outside `[1, 50]` shall be rejected with HTTP 400 Bad Request (`title: "Solicitud inválida"`, `detail: "El límite de paginación debe estar entre 1 y 50"`).
  - `unreadOnly` (optional, default `false`): boolean flag that, when `true`, restricts results to rows with `is_read = false`.
  - Each `NotificationResponse` record shall expose `{ "id", "eventType", "content", "read", "createdAt" }`.
- **NTF-REQ-21 (Event-driven):** When an authenticated user requests `GET /api/v1/notifications/unread-count`, the system shall return HTTP 200 OK with `{ "unreadCount": <long> }` reflecting the exact count of unread notifications (`is_read = false`) owned by the authenticated user.
- **NTF-REQ-22 (Event-driven):** When an authenticated user sends `PATCH /api/v1/notifications/{notificationId}/read` for an existing owned notification, the system shall set `is_read = true` (remaining idempotent if already `true`) and return HTTP 200 OK with the updated `NotificationResponse`.
- **NTF-REQ-23 (Event-driven):** When an authenticated user sends `PATCH /api/v1/notifications/read-all`, the system shall atomically mark all unread notifications (`is_read = false`) belonging exclusively to the authenticated user as read (`is_read = true`) and return HTTP 200 OK containing `{ "updatedCount": <long> }` (`0` when no unread notifications exist).
- **NTF-REQ-24 (Event-driven):** When an authenticated user sends `DELETE /api/v1/notifications/{notificationId}` for an existing owned notification, the system shall permanently delete that notification record and return HTTP 204 No Content.
- **NTF-REQ-25 (Unwanted event):** If an authenticated user invokes `PATCH /api/v1/notifications/{notificationId}/read` or `DELETE /api/v1/notifications/{notificationId}` for a non-existent ID or a notification owned by another user, then the system shall enforce strict `404` -> `403` precedence and return a localized Spanish RFC 7807 `ProblemDetail` (HTTP 404 `"Notificación no encontrada"` if absent; HTTP 403 `"No tiene permisos para realizar esta acción"` if owned by another user).
- **NTF-REQ-26 (Ubiquitous):** The system shall schedule and execute a persistent `db-scheduler` recurring retention task (`task_name = "notification-retention-cleanup"`, running daily) that permanently deletes all `notifications` rows (both read and unread) where `created_at < DateTimeProvider.now().minus(30, ChronoUnit.DAYS)` (`FND-REQ-08`, 1-month retention rule per SRS Appendix B).

### 3.4 Seguridad, CSRF y Contratos RFC 7807 (`RNF-07`, `RNF-08`, `RNF-14`)

- **NTF-REQ-27 (Unwanted event):** If an unauthenticated request targets any endpoint under `/api/v1/notifications/**`, then the system shall return HTTP 401 Unauthorized with Spanish RFC 7807 `ProblemDetail` (`title: "No autenticado"`, `detail: "Debe iniciar sesión para acceder a este recurso"`).
- **NTF-REQ-28 (Unwanted event):** If an authenticated request performs any state-mutating method (`POST`, `PATCH`, `DELETE`) under `/api/v1/notifications/**` without a valid CSRF token, then the system shall reject it with HTTP 403 Forbidden (`title: "Acceso denegado"`, `detail: "Token CSRF ausente o inválido para la solicitud."`).
- **NTF-REQ-29 (Unwanted event):** If a request under `/api/v1/notifications/**` contains an unreadable or malformed JSON body or invalid path/query parameter types (e.g., non-numeric `notificationId`/`subscriptionId`, non-integer `limit`, or non-boolean `unreadOnly`), then the system shall return HTTP 400 Bad Request with Spanish RFC 7807 `ProblemDetail` (`title: "Solicitud inválida"`, `detail: "El cuerpo o parámetro de la solicitud no tiene un formato válido"`), while Bean Validation payload failures return HTTP 400 Bad Request (`title: "Error de validación"`, `detail: "Uno o más campos contienen errores de validación"` with `invalidParams`).

---

## 4. Escenarios Borde y Casos Extremos (Edge Cases)

- **EC-01 (Multi-dispositivo con suscripción expirada `404`/`410`):** Un usuario mantiene activas una suscripción en escritorio (`A`) y otra obsoleta en móvil (`B`). Al dispararse el job de vencimiento, el servicio push devuelve `201` para `A` y `410 Gone` para `B`. La notificación in-app se conserva intacta, `A` recibe el aviso, `B` es eliminada automáticamente de `push_subscriptions` y el job concluye sin errores.
- **EC-02 (Re-vinculación de `endpoint` en navegador compartido sin logout previo):** El usuario A registra el `endpoint X`; luego el usuario B inicia sesión en el mismo navegador sin que A haya cerrado sesión y envía `POST /api/v1/notifications/push/subscriptions` con `endpoint X`. Al actualizarse `user_id` a B sobre la fila única (`200 OK`), el usuario A deja de recibir avisos en ese navegador y únicamente el usuario B recibe los suyos.
- **EC-03 (Rotación idempotente de claves para el mismo usuario):** El Service Worker del navegador renueva sus claves (`p256dh`/`auth`) conservando el mismo `endpoint` y vuelve a invocar `POST /api/v1/notifications/push/subscriptions`. El sistema actualiza las claves en el registro existente devolviendo `200 OK` sin duplicar filas ni provocar errores `409`/`500`.
- **EC-04 (Tarea completada o eliminada en ventana concurrente de disparo):** Un usuario marca como completada o elimina una tarea instantes antes de que `db-scheduler` procese `task-due-{taskId}`. Al cargar la tarea desde PostgreSQL y detectar ausencia o `completed == true`, el handler finaliza como `no-op` sin insertar notificación in-app ni emitir Web Push.
- **EC-05 (Postergación concurrente de fecha/hora o ventana previa a `09:00 AM`):** Una tarea vencía hoy a las `14:00` y su job estaba próximo a ejecutarse, pero el usuario pospone su fecha a la próxima semana, o bien elimina `dueTime` antes de las `09:00:00` locales de hoy (o cambia su zona horaria de modo que `09:00` local quede en el futuro). Al recalcular el disparador con `DateTimeProvider.calculateDueTriggerInstant(...)` y detectar un instante futuro `> now + 1s`, el sistema reprograma el job al instante actualizado sin emitir notificación anticipada.
- **EC-06 (Comportamiento ante tareas creadas vencidas vs. solo-fecha hoy después de las 09:00 AM):** De acuerdo con `003-tasks-and-tags`, las tareas que nacen ya vencidas (`dueDate` pasada o `dueDate+dueTime` anterior a `now`) no programan job y no generan notificaciones retroactivas. Las tareas solo-fecha para el día actual creadas después de las `09:00 AM` (aún vigentes hasta las `23:59:59.999`) disparan su job de inmediato tras crearse y generan su notificación in-app + push normalmente.
- **EC-07 (VAPID deshabilitado, parcial o malformado y caída temporal del push gateway externo):** En entornos sin claves VAPID (`enabled = false`), o incluso con `enabled = true` pero configuración parcial (faltante `publicKey`, `privateKey` o `subject`) o claves EC P-256 inválidas/no emparejadas, la aplicación arranca con advertencia en logs sin exponer secretos, `GET /api/v1/notifications/push/vapid-public-key` retorna `{ "enabled": false, "publicKey": null }`, las suscripciones se gestionan normalmente y al vencer una tarea la notificación in-app en `notifications` se guarda íntegra. Ante un timeout o error `5xx`/`429` del servidor push externo, tampoco se revierte la fila en `notifications` ni se re-inserta por duplicado.
- **EC-08 (Privacidad en `DELETE` por `endpoint` frente a precedencia `404` -> `403` por ID):** Invocar `DELETE /api/v1/notifications/push/subscriptions?endpoint=E` cuando `E` pertenece a otro usuario responde `204 No Content` de forma idempotente sin borrar la fila ajena ni revelar su existencia; si se omite o queda vacío tras `trim()`, retorna `400 Bad Request` en español (`Solicitud inválida`). En contraste, invocar `DELETE /api/v1/notifications/push/subscriptions/{id}`, `PATCH /api/v1/notifications/{id}/read` o `DELETE /api/v1/notifications/{id}` devuelve estrictamente `404` si el ID no existe y `403` si pertenece a otro usuario.
- **EC-09 (Localización `RNF-14`/`RF-04` y truncamiento seguro `VARCHAR(500)`):** El contenido de `notifications.content` y `body` de Web Push formatea siempre fechas como `dd/MM/yyyy`, respeta `time_format` del destinatario (`HH:mm` para `24H`, `h:mm a` `AM`/`PM` para `12H`) y omite toda hora en tareas solo-fecha sin fabricar `00:00`. Incluso con títulos de 100 caracteres y grupos de 30 caracteres con saltos de línea, el texto en español normaliza espacios y trunca con `"..."` garantizando `<= 500` caracteres.
- **EC-10 (Estabilidad del paginado Keyset bajo nuevas notificaciones):** Mientras un usuario navega `GET /api/v1/notifications?limit=20` desde la página 1 hacia la página 2, si vence una nueva tarea (insertándose al inicio del historial), el cursor `NotificationCursor(createdAt, id)` evita saltos o duplicados de registros anteriores entre páginas.
- **EC-11 (Purga automática por retención de 30 días calendario):** La tarea diaria `notification-retention-cleanup` elimina tanto notificaciones leídas como no leídas con `created_at < now - 30 días`, preservando intactas todas las notificaciones dentro de la ventana de los últimos 30 días y evitando colisiones en despliegues multi-instancia gracias al bloqueo en `scheduled_tasks`.
- **EC-12 (Logout multi-dispositivo y eliminación total de cuenta):** Al cerrar sesión en el dispositivo móvil enviando su `endpoint` en `POST /api/v1/auth/logout`, solo se elimina esa suscripción y permanece activa la del navegador de escritorio. Al eliminar la cuenta (`DELETE /api/v1/users/me`), la cascada elimina suscripciones, notificaciones y trabajos `scheduled_tasks` asociados sin dejar datos huérfanos.

---

## 5. Matriz de Trazabilidad (Requirements Traceability)

| ID Requisito | Origen (SRS / SVD / Foundation) | Capacidad / Endpoint / Componente | Verificación Esperada |
| :--- | :--- | :--- | :--- |
| **NTF-REQ-01** | RF-27, RNF-08, FND-REQ-34, FND-REQ-35 | `GET /api/v1/notifications/push/vapid-public-key` (habilitado / deshabilitado / parcial-inválido) | Test unitario + integración |
| **NTF-REQ-02** | RF-27, RNF-07 | `POST /api/v1/notifications/push/subscriptions` (nueva, `trim()`, ignora extra) | Test integración (`201 Created`, persistencia DB) |
| **NTF-REQ-03** | RF-27 | `POST /api/v1/notifications/push/subscriptions` (idempotente) | Test integración (`200 OK`, rotación de claves) |
| **NTF-REQ-04** | RF-27, V1 UNIQUE | `POST /api/v1/notifications/push/subscriptions` (re-vinculación) | Test integración (cambio de dueño en `endpoint`) |
| **NTF-REQ-05** | RF-27 | Soporte multi-dispositivo por usuario | Test integración (2+ endpoints por usuario) |
| **NTF-REQ-06** | RF-27, RNF-07, RNF-14 | Validación URL, `trim()`, límites `255`/`2048`, llaves Base64URL RFC 8291 | Test unitario + integración (`400` español) |
| **NTF-REQ-07** | RF-27 | `DELETE /api/v1/notifications/push/subscriptions/{subscriptionId}` | Test integración (`204 No Content`) |
| **NTF-REQ-08** | RNF-07, RNF-14, RNF-18 | Precedencia `404` -> `403` al borrar suscripción por ID | Test integración (`404` / `403` español) |
| **NTF-REQ-09** | RF-27, RNF-14 | `DELETE /api/v1/notifications/push/subscriptions?endpoint=...` (`trim()`, `400` explícito) | Test integración (`204` idempotente / `400` vacío) |
| **NTF-REQ-10** | RF-02, RF-05, RNF-10 | Compatibilidad `POST /auth/logout` y `DELETE /users/me` | Regresión tests `001-auth-and-users` |
| **NTF-REQ-11** | RF-28, FND-REQ-30, TSK-REQ-11 | Handler `db-scheduler` `task-due-notification` | Test integración con `db-scheduler` / Testcontainers |
| **NTF-REQ-12** | RF-28, FND-REQ-31 | Ignorar tarea eliminada, completada o sin `dueDate` (`no-op`) | Test unitario + integración |
| **NTF-REQ-13** | RF-28, FND-REQ-28..31 | Reprogramación vía `DateTimeProvider.calculateDueTriggerInstant` (`> now + 1s`, incl. hoy pre-09:00) | Test unitario con `Clock` / `ZoneId` |
| **NTF-REQ-14** | RF-28, RNF-20 | Destinatario: `assignedUser` o `group.creator` en MVP | Test unitario |
| **NTF-REQ-15** | RF-29, RF-04, RNF-14, Riesgo 1 | Persistencia `TASK_DUE` (`dd/MM/yyyy`, `24H`/`12H`, sin `00:00` ficticio, `<= 500`) | Test unitario + integración (commit previo al push HTTP) |
| **NTF-REQ-16** | RF-27, RF-28, RNF-14 | Cifrado RFC 8291 (`aes128gcm`), VAPID RFC 8292, `body == content` | Test unitario crypto + WireMock/Mock HTTP push |
| **NTF-REQ-17** | RF-27 | Auto-limpieza de suscripciones con HTTP `404`/`410` | Test integración con servidor HTTP simulado |
| **NTF-REQ-18** | RF-27, Riesgo 1 | Tolerancia a fallos transitorios y VAPID deshabilitado/parcial/inválido | Test integración (sin duplicar in-app) |
| **NTF-REQ-19** | RF-29, RNF-04, FND-REQ-22, FND-REQ-25 | `GET /api/v1/notifications` (`created_at DESC, id DESC`, Keyset) | Test integración multi-página y desempate `id` |
| **NTF-REQ-20** | RF-29, RNF-04, RNF-14 | Parámetros `cursor` (incl. blank/null fields), `limit` (`1..50`), `unreadOnly`, DTO | Test integración (`200` y `400` español) |
| **NTF-REQ-21** | RF-29 | `GET /api/v1/notifications/unread-count` | Test integración |
| **NTF-REQ-22** | RF-29 | `PATCH /api/v1/notifications/{notificationId}/read` | Test integración (marcado + idempotencia) |
| **NTF-REQ-23** | RF-29 | `PATCH /api/v1/notifications/read-all` | Test integración (`updatedCount`) |
| **NTF-REQ-24** | RF-29 | `DELETE /api/v1/notifications/{notificationId}` | Test integración (`204 No Content`) |
| **NTF-REQ-25** | RNF-07, RNF-14, RNF-18 | Precedencia `404` -> `403` en centro de notificaciones | Test integración (`404` / `403` español) |
| **NTF-REQ-26** | RF-29, FND-REQ-08, Apéndice B | Job recurrente `notification-retention-cleanup` (> 30 días) | Test integración con `DateTimeProvider` / `Clock` |
| **NTF-REQ-27** | RNF-08, FND-REQ-16 | Protección `401` en `/api/v1/notifications/**` | Test integración seguridad |
| **NTF-REQ-28** | RNF-08, FND-REQ-11 | Protección CSRF `403` en mutaciones `POST`/`PATCH`/`DELETE` | Test integración CSRF |
| **NTF-REQ-29** | RNF-07, RNF-14, FND-REQ-17 | Contrato `400 Bad Request` (`Solicitud inválida` / `Error de validación`) JSON y parámetros | Test integración validación / parsing |
