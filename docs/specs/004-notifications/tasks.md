# Lista de Tareas: 004-notifications (Notificaciones Push Web, Jobs de Vencimiento y Centro de Notificaciones)

**Fase:** 004-notifications  
**Estado:** completed  
**Fecha:** 2026-11-18  
**Autor:** SDD Planner Agent  
**Referencias:**
- `docs/specs/004-notifications/spec.md` (NTF-REQ-01 a NTF-REQ-29, EC-01 a EC-12)
- `docs/specs/004-notifications/plan.md` (Diseño técnico y arquitectura)
- `docs/constitution.md` (Principios 1 a 6)
- `AGENTS.md` (Comandos de verificación y domain rules)
- `src/main/resources/db/migration/V1__init_schema.sql` (Esquema DDL de referencia)

---

## Tareas de Implementación

- [x] **T1: Fundaciones compartidas, propiedades VAPID (`VapidProperties` / `VapidConfigurationState`) y configuración de pruebas (`shared/`, `notification/`, `yml`)**
  - **Requisitos cubiertos:** NTF-REQ-01, NTF-REQ-09, NTF-REQ-18, NTF-REQ-20, NTF-REQ-29, EC-07, FND-REQ-34, FND-REQ-35.
  - **Archivos a crear/modificar:**
    - `src/main/resources/application.yml`
    - `src/test/resources/application-test.yml`
    - `src/main/java/com/eyelesscode/task_by_task/shared/error/GlobalExceptionHandler.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/pagination/KeysetCursorCodec.java` (verificar y reforzar validación explícita en `case "NOTIFICATION"` para `createdAt == null || id == null`)
    - `src/main/java/com/eyelesscode/task_by_task/notification/VapidProperties.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/WebPushCryptoUtils.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/VapidConfigurationState.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/WebPushConfig.java`
    - `src/test/java/com/eyelesscode/task_by_task/shared/error/GlobalExceptionHandlerTest.java`
    - `src/test/java/com/eyelesscode/task_by_task/shared/pagination/KeysetCursorCodecTest.java`
    - `src/test/java/com/eyelesscode/task_by_task/notification/VapidConfigurationStateTest.java`
  - **Descripción:**
    - Configurar `app.web-push.{enabled, vapid.public-key, vapid.private-key, vapid.subject}` mediante variables de entorno en `application.yml` y fijar `db-scheduler.enabled: false` y `app.web-push.enabled: false` en `application-test.yml` para evitar que el scheduler en segundo plano interfiera con relojes mockeados de pruebas existentes.
    - Añadir `@ExceptionHandler(MissingServletRequestParameterException.class)` en `GlobalExceptionHandler` devolviendo HTTP `400` (`title: "Solicitud inválida"`, `detail: "El parámetro '" + name + "' es obligatorio y no puede estar vacío"`).
    - Verificar en `KeysetCursorCodec` el rechazo de `NotificationCursor` con `createdAt == null` o `id == null` incluso en tokens con checksum CRC32 válido y añadir la prueba correspondiente en `KeysetCursorCodecTest`.
    - Crear `VapidProperties`, `WebPushCryptoUtils` (usando instancia local de `BouncyCastleProvider` para validar Base64/Base64URL, punto P-256 65B `0x04||X||Y`, clave privada 32B y emparejamiento `d * G == Q`, además de secretos `auth` 16B) y `VapidConfigurationState` (degradación segura a `enabled = false, publicKey = null` con logs de advertencia sin exponer claves privadas cuando VAPID está deshabilitado, parcial o inválido/no emparejado). Registrar `@EnableConfigurationProperties(VapidProperties.class)` y bean `HttpClient` en `WebPushConfig`.
    - Escribir pruebas unitarias en `GlobalExceptionHandlerTest`, `KeysetCursorCodecTest` y `VapidConfigurationStateTest`.
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test -Dtest=GlobalExceptionHandlerTest,KeysetCursorCodecTest,VapidConfigurationStateTest` pasan al 100%.

- [x] **T2: Gestión de suscripciones Web Push y endpoint VAPID (`GET /vapid-public-key`, `POST/DELETE /push/subscriptions`)**
  - **Requisitos cubiertos:** NTF-REQ-01 a NTF-REQ-10, NTF-REQ-27, NTF-REQ-28, NTF-REQ-29, EC-02, EC-03, EC-08, EC-12.
  - **Archivos a crear/modificar:**
    - `src/main/java/com/eyelesscode/task_by_task/notification/PushSubscriptionRepository.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/dto/VapidPublicKeyResponse.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/dto/ValidPushEndpoint.java` y `ValidPushEndpointValidator.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/dto/ValidP256PublicKey.java` y `ValidP256PublicKeyValidator.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/dto/ValidPushAuthSecret.java` y `ValidPushAuthSecretValidator.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/dto/RegisterPushSubscriptionRequest.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/dto/PushSubscriptionResponse.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/PushSubscriptionMapper.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/PushSubscriptionService.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/NotificationController.java`
    - `src/test/java/com/eyelesscode/task_by_task/notification/RegisterPushSubscriptionRequestValidationTest.java`
    - `src/test/java/com/eyelesscode/task_by_task/notification/PushSubscriptionIntegrationTest.java`
  - **Descripción:**
    - Mantener `deleteByUserIdAndEndpoint` en `PushSubscriptionRepository` (usado por `AuthService.logout`) y añadir `findByEndpoint`, `findByIdWithUser`, `findAllByUserId` y `deleteAllByIdIn`.
    - Implementar DTOs con `trim()` en `endpoint`, `keys.p256dh` y `keys.auth`, validadores RFC 8291 (`http`/`https` <= 2048, P-256 65B <= 255, auth 16B <= 255), `@JsonInclude(ALWAYS)` en `VapidPublicKeyResponse.publicKey`, y `PushSubscriptionResponse` sin claves criptográficas.
    - Implementar `PushSubscriptionService` con UPSERT nativo PostgreSQL `ON CONFLICT (endpoint) DO UPDATE ... RETURNING id, endpoint, created_at, (xmax = 0) AS inserted` (respondiendo `201 Created` en alta, `200 OK` en actualización idempotente del mismo dueño y `200 OK` en re-vinculación de navegador compartido), `deleteById` con precedencia `404` -> `403`, y `deleteByEndpoint` con `trim()`, rechazo `400` si falta o está vacío y `204` idempotente sin afectar otros usuarios.
    - Exponer `GET /api/v1/notifications/push/vapid-public-key`, `POST /api/v1/notifications/push/subscriptions`, `DELETE /api/v1/notifications/push/subscriptions/{subscriptionId}` y `DELETE /api/v1/notifications/push/subscriptions` en `NotificationController`.
    - Crear pruebas unitarias (`RegisterPushSubscriptionRequestValidationTest`) e integración (`PushSubscriptionIntegrationTest`, incluyendo verificación del ignorado de campos W3C `expirationTime`/campos de servidor y regresión junto a `POST /api/v1/auth/logout`).
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test -Dtest=RegisterPushSubscriptionRequestValidationTest,PushSubscriptionIntegrationTest,AuthLoginLogoutIntegrationTest` pasan al 100%.

- [x] **T3: Cifrado Web Push RFC 8291 (`aes128gcm`), firma VAPID RFC 8292 (`ES256`) y transporte HTTP**
  - **Requisitos cubiertos:** NTF-REQ-16, NTF-REQ-17, NTF-REQ-18, EC-01, EC-07.
  - **Archivos a crear:**
    - `src/main/java/com/eyelesscode/task_by_task/notification/WebPushPayload.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/WebPushEncryptionService.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/WebPushTransport.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/HttpClientWebPushTransport.java`
    - `src/test/java/com/eyelesscode/task_by_task/notification/WebPushEncryptionServiceTest.java`
    - `src/test/java/com/eyelesscode/task_by_task/notification/HttpClientWebPushTransportTest.java`
  - **Descripción:**
    - Implementar `WebPushEncryptionService`: derivación ECDH P-256 + HKDF-SHA256 + `AES/GCM/NoPadding` (`aes128gcm` con cabecera RFC 8188 de 86 bytes y delimitador `0x02`), y generación de cabecera `Authorization: vapid t=..., k=...` con JWT `ES256` firmado mediante `SHA256withPLAIN-ECDSA` (64 bytes IEEE P1363 `r || s`, `aud` origen URI, `exp = now + 12h`, `sub`).
    - Implementar `WebPushTransport` y `HttpClientWebPushTransport` usando `java.net.http.HttpClient` con timeouts explícitos, mapeando `2xx` -> éxito, `404`/`410` -> suscripción expirada, y `429`/`5xx`/excepción de red -> fallo transitorio no bloqueante.
    - Escribir pruebas unitarias con descifrado inverso real del lado cliente y verificación real de firma JWT ES256 (`WebPushEncryptionServiceTest`), y pruebas contra `com.sun.net.httpserver.HttpServer` embebido local (`HttpClientWebPushTransportTest`).
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test -Dtest=WebPushEncryptionServiceTest,HttpClientWebPushTransportTest` pasan al 100%.

- [x] **T4: Entidad `Notification`, formateo localizado (`RNF-14`/`RF-04`), repositorio Keyset y consulta de tarea para notificación**
  - **Requisitos cubiertos:** NTF-REQ-14, NTF-REQ-15, NTF-REQ-19, NTF-REQ-20, NTF-REQ-21, NTF-REQ-23, NTF-REQ-26, EC-09, EC-10, EC-11, RNF-20.
  - **Archivos a crear/modificar:**
    - `src/main/java/com/eyelesscode/task_by_task/notification/NotificationEventType.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/Notification.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/NotificationRepository.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/NotificationContentFormatter.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/NotificationMapper.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/dto/NotificationResponse.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/dto/UnreadCountResponse.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/dto/MarkAllReadResponse.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/TaskRepository.java`
    - `src/test/java/com/eyelesscode/task_by_task/notification/NotificationContentFormatterTest.java`
  - **Descripción:**
    - Mapear `Notification` contra tabla `notifications` (`id`, `user`, `event_type VARCHAR(50)`, `content VARCHAR(500)`, `is_read`, `created_at`) y validar compatibilidad `ddl-auto=validate`.
    - Crear `NotificationRepository` con `findByIdWithUser`, `findFirstPage` / `findNextPage` (`createdAt DESC, id DESC` y `:unreadOnly`), `countByUserIdAndReadFalse`, `markAllAsReadByUserId` y `deleteByCreatedAtBefore`.
    - Añadir `findByIdForDueNotification` (`JOIN FETCH t.group g JOIN FETCH g.creator LEFT JOIN FETCH t.assignedUser`) en `TaskRepository`.
    - Implementar `NotificationContentFormatter` cumpliendo `RNF-14` (`dd/MM/yyyy`), `RF-04` (`HH:mm` para `24H`, `h:mm a` `AM`/`PM` para `12H`, sin hora ficticia `00:00`/`09:00` en solo-fecha), limpieza de saltos de línea y truncamiento seguro `<= 500` caracteres (`...`), más `NotificationMapper` y DTOs de respuesta.
    - Escribir pruebas unitarias exhaustivas en `NotificationContentFormatterTest` y validar arranque/schema en `ApplicationIntegrationTest`.
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test -Dtest=NotificationContentFormatterTest,ApplicationIntegrationTest` pasan al 100%.

- [x] **T5: Centro de Notificaciones REST API (`GET /notifications`, `/unread-count`, `PATCH /read`, `PATCH /read-all`, `DELETE /{id}`) y retención 30 días**
  - **Requisitos cubiertos:** NTF-REQ-19 a NTF-REQ-29, EC-08, EC-10, EC-11.
  - **Archivos a crear/modificar:**
    - `src/main/java/com/eyelesscode/task_by_task/notification/NotificationService.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/NotificationController.java`
    - `src/test/java/com/eyelesscode/task_by_task/notification/NotificationCenterIntegrationTest.java`
  - **Descripción:**
    - Implementar `NotificationService`: `listNotifications` (`limit 1..50` por defecto `20`, decodificación de `NotificationCursor` vía `KeysetCursorCodec`, filtro `unreadOnly`, paginación Keyset `limit + 1`), `getUnreadCount`, `markAsRead` (idempotente, `404` -> `403`), `markAllAsRead` (retornando `updatedCount`), `deleteNotification` (`404` -> `403`), y `purgeExpiredNotifications()` (`createdAt < now.truncatedTo(MICROS).minus(30, ChronoUnit.DAYS)`).
    - Exponer los cinco endpoints del Centro de Notificaciones en `NotificationController`.
    - Crear `NotificationCenterIntegrationTest` cubriendo orden `created_at DESC, id DESC`, desempate por `id`, estabilidad ante inserciones nuevas (`EC-10`), `unreadOnly`, contadores, marcado individual idempotente y masivo, eliminación, precedencia `404` -> `403`, cursores corruptos/blank/campos nulos/tipo incorrecto, `limit` inválido, parámetros mal tipados (`400 Solicitud inválida`), seguridad `401`/CSRF `403` y purga exacta de 30 días calendario (leídas y no leídas).
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test -Dtest=NotificationCenterIntegrationTest` pasan al 100%.

- [x] **T6: Ejecución `db-scheduler` de vencimientos (`task-due-notification`), retención diaria y entrega Web Push end-to-end**
  - **Requisitos cubiertos:** NTF-REQ-11 a NTF-REQ-18, NTF-REQ-26, EC-01, EC-04, EC-05, EC-06, EC-07, EC-11, RNF-20.
  - **Archivos a crear:**
    - `src/main/java/com/eyelesscode/task_by_task/notification/DueNotificationJobService.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/NotificationSchedulerConfig.java`
    - `src/test/java/com/eyelesscode/task_by_task/notification/DueNotificationJobServiceTest.java`
    - `src/test/java/com/eyelesscode/task_by_task/notification/DueNotificationDeliveryIntegrationTest.java`
    - `src/test/java/com/eyelesscode/task_by_task/notification/NotificationSchedulerIntegrationTest.java`
  - **Descripción:**
    - Implementar `DueNotificationJobService` separando estrictamente TX1 (validaciones `NTF-REQ-12`, cálculo de postergación `NTF-REQ-13` con `DateTimeProvider.calculateDueTriggerInstant`, resolución `RNF-20`, guardado de `Notification` con `createdAt` en `MICROS` y lectura de snapshot de suscripciones) -> envío HTTP best-effort cifrado fuera de transacción -> TX2 (eliminación de suscripciones `404`/`410`).
    - Registrar en `NotificationSchedulerConfig` `CustomTask<Void>` para `TaskDueSchedulePort.TASK_NAME` (`"task-due-notification"`, retornando `OnCompleteRemove` o `operations.reschedule(...)`) y `RecurringTask<Void>` `"notification-retention-cleanup"` (`Schedules.daily(ZoneOffset.UTC, LocalTime.of(3, 0))`).
    - Crear `DueNotificationJobServiceTest` (unitarios cubriendo todos los casos borde `NTF-REQ-12..14`, hora local pre-09:00 AM y cambio de zona), `DueNotificationDeliveryIntegrationTest` (Testcontainers + `HttpServer` local + claves VAPID P-256 reales de prueba validando descifrado real, auto-limpieza `404`/`410`, tolerancia a `500`/`429`/timeout y VAPID deshabilitado/parcial/malformado), y `NotificationSchedulerIntegrationTest` (habilitando `db-scheduler.enabled=true` y `polling-interval=200ms` con `Awaitility` para probar disparo automático real desde `TaskDueSchedulePort`, reprogramación `NTF-REQ-13` y registro/ejecución del job recurrente de retención).
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test -Dtest=DueNotificationJobServiceTest,DueNotificationDeliveryIntegrationTest,NotificationSchedulerIntegrationTest` pasan al 100%.

- [x] **T7: Verificación integral de calidad, formato Spotless, regresión completa del proyecto y actualización de `MEMORY.md`**
  - **Requisitos cubiertos:** Todos los requisitos del módulo (`NTF-REQ-01` a `NTF-REQ-29`, `EC-01` a `EC-12`), Constitución Principios 1 a 6.
  - **Archivos a modificar/verificar:**
    - `docs/specs/004-notifications/plan.md` (`**Estado:** completed`)
    - `docs/specs/004-notifications/tasks.md` (`**Estado:** completed` y todas las casillas `[x]`)
    - `MEMORY.md`
    - Todo el repositorio.
  - **Descripción:** Ejecutar `./mvnw spotless:apply` seguido de `./mvnw spotless:check compile test-compile`. Ejecutar `./mvnw test` verificando que el 100% de las pruebas unitarias y de integración con Testcontainers (incluyendo regresión completa de `000`, `001`, `002`, `003` y `004`) pasen sin fallos ni omisiones. Actualizar `MEMORY.md` documentando la finalización de `004-notifications`, decisiones clave (`VapidConfigurationState`, UPSERT atómico de `push_subscriptions`, `CustomTask` + aislamiento TX1/HTTP/TX2 en `db-scheduler`, RFC 8291/8292 en Java puro + BC, y `db-scheduler.enabled=false` por defecto en `application-test.yml`), el nuevo conteo total de pruebas, y corregir cualquier referencia menor de `RF` de `003-tasks-and-tags` si fuese imprecisa (`RF-22` / `RF-12..16`).
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test` finalizan con éxito (`BUILD SUCCESS`) y `MEMORY.md`, `plan.md` y `tasks.md` reflejan el cierre completo.
