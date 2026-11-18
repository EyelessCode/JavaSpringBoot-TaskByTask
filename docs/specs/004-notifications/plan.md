# Plan Técnico de Implementación: 004-notifications (Notificaciones Push Web, Jobs de Vencimiento y Centro de Notificaciones)

**Fase:** 004-notifications  
**Estado:** completed  
**Fecha:** 2026-11-18  
**Autor:** SDD Planner Agent  
**Referencias:**
- `docs/specs/004-notifications/spec.md` (NTF-REQ-01 a NTF-REQ-29, EC-01 a EC-12)
- `docs/specs/000-foundation/spec.md` y `plan.md` (Esquema V1, `KeysetCursorCodec`, `DateTimeProvider`, `GlobalExceptionHandler`, `FND-REQ-08`, `FND-REQ-25`, `FND-REQ-34`, `FND-REQ-35`)
- `docs/specs/001-auth-and-users/spec.md` (`POST /api/v1/auth/logout` con `endpoint` opcional y `DELETE /api/v1/users/me`)
- `docs/specs/003-tasks-and-tags/spec.md` y `plan.md` (Frontera `TaskDueSchedulePort` / `JdbcTaskDueScheduleAdapter` sobre `scheduled_tasks`)
- `docs/constitution.md` (Principios 1 a 6)
- `AGENTS.md` (Convenciones Pure Java 21, regla 09:00 AM, extensibilidad `RNF-20`, localización española RFC 7807)
- `src/main/resources/db/migration/V1__init_schema.sql` (`push_subscriptions`, `notifications`, `scheduled_tasks`)

---

## 1. Resumen Ejecutivo y Enfoque de Diseño

El módulo **004-notifications** cierra el ciclo de cumplimiento de tareas del MVP combinando Web Push cifrado (RFC 8291/8292), consumo persistente de trabajos `db-scheduler` 15.0.0 y un Centro de Notificaciones Keyset con purga automática a los 30 días, operando íntegramente sobre `V1__init_schema.sql` sin migraciones SQL adicionales y sin nuevas dependencias en `pom.xml`.

El diseño técnico se apoya en seis pilares:

1. **Dependencia acíclica entre paquetes (`auth -> notification -> task, group, user, shared`):**
   `AuthService` ya inyecta `PushSubscriptionRepository` para la limpieza selectiva en logout (`AUTH-REQ-12`). Para evitar ciclos de dependencias, ningún componente dentro de `notification/` importará ni dependerá de `auth/`. `notification/` solo consume `task/` (`Task`, `TaskRepository`, `TaskDueSchedulePort`), `group/` (`Group.creator` vía `Task`), `user/` (`User`, `UserRepository`) y `shared/` (`DateTimeProvider`, `KeysetCursorCodec`, `KeysetPage`, `SecurityUtils`, excepciones RFC 7807).
2. **Validación y degradación segura de VAPID (`VapidProperties` + `VapidConfigurationState`, `NTF-REQ-01`/`18`/`EC-07`):**
   Las propiedades `app.web-push.enabled`, `app.web-push.vapid.public-key`, `private-key` y `subject` se inyectan exclusivamente vía entorno (`application.yml`, `FND-REQ-34`/`35`). Al construir `VapidConfigurationState`, se valida la URI del `subject` (`mailto:`/`https:`), la curva P-256 (`secp256r1`) del par de claves y la correspondencia matemática `d * G == Q`. Si `enabled=false` o la configuración es parcial o inválida/no emparejada, se registra una advertencia segura (sin exponer secretos), la aplicación arranca sin fallo, `GET /api/v1/notifications/push/vapid-public-key` responde `{ "enabled": false, "publicKey": null }` y la persistencia in-app continúa intacta.
3. **Upsert atómico y re-vinculación en `push_subscriptions` (`NTF-REQ-02`..`04`, `EC-02`/`03`):**
   Dado el índice global `endpoint TEXT NOT NULL UNIQUE`, el registro ejecuta un `INSERT ... ON CONFLICT (endpoint) DO UPDATE ... RETURNING id, endpoint, created_at, (xmax = 0) AS inserted` en PostgreSQL: evita colisiones concurrentes que abortarían sesiones Hibernate, actualiza claves idempotentemente para el mismo dueño preservando `created_at`, re-asigna `user_id` y refresca `created_at` si otro usuario toma un navegador compartido, y determina en un único viaje a BD si retornar `201 Created` o `200 OK`.
4. **Aislamiento transaccional en `task-due-notification` usando `CustomTask<Void>` de `db-scheduler` (`NTF-REQ-11`..`18`):**
   En `db-scheduler` 15.0.0, `OneTimeTask<Void>` siempre ejecuta `OnCompleteRemove` al retornar del handler, por lo que no permite reprogramar atópicamente la misma fila cuando detecta postergación concurrente (`NTF-REQ-13`, `EC-05`). Al registrar `"task-due-notification"` mediante `Tasks.custom(TaskDueSchedulePort.TASK_NAME, Void.class)` (`CustomTask<Void>`):
   - Si la tarea ya venció y debe notificarse (`NTF-REQ-15`): una **TX1** corta persiste primero la fila en `notifications` (con `created_at` truncado a `ChronoUnit.MICROS` y texto localizado `RNF-14`/`RF-04` `<= 500` chars) y extrae un snapshot inmutable de suscripciones activas; la entrega HTTP RFC 8291/8292 se ejecuta **fuera de toda transacción de BD** (sin retener conexiones PostgreSQL durante I/O de red); una **TX2** corta elimina suscripciones `404`/`410` (`NTF-REQ-17`); y el handler retorna `CompletionHandler.OnCompleteRemove` sin relanzar fallos transitorios (`NTF-REQ-18`).
   - Si la tarea fue pospuesta a futuro (`DateTimeProvider.calculateDueTriggerInstant(...) > now + 1s`, `NTF-REQ-13`): el handler retorna `(completion, operations) -> operations.reschedule(completion, recalculatedInstant)` preservando integridad de bloqueo optimista (`version`) en `scheduled_tasks`.
5. **Cifrado Web Push RFC 8291 (`aes128gcm`) y VAPID RFC 8292 (`ES256`) en Java puro + BouncyCastle (`bcprov-jdk18on`):**
   Se implementa `WebPushEncryptionService` sin dependencias externas nuevas usando `javax.crypto` (`ECDH`, `HmacSHA256` para HKDF, `AES/GCM/NoPadding`), `java.security` + instancia local dedicada de `BouncyCastleProvider` (sin alterar el registro global de proveedores JCA del runtime) para validar puntos `secp256r1` y firmar JWT `ES256` en formato IEEE P1363 exacto de 64 bytes `(r || s)` (`SHA256withPLAIN-ECDSA`), enviado sobre `java.net.http.HttpClient` con timeouts explícitos tras un puerto `WebPushTransport` verificable con `com.sun.net.httpserver.HttpServer`.
6. **Aislamiento de pruebas con `db-scheduler` (`application-test.yml` vs `NotificationSchedulerIntegrationTest`):**
   Las suites existentes de `001`–`003` (`GroupRenameAndDeleteIntegrationTest`, `UserAccountDeletionIntegrationTest`, `Task*IntegrationTest`) insertan/verifican filas en `scheduled_tasks` con fechas simuladas `2026-11-15` mediante `@MockitoBean Clock`, mientras `db-scheduler` evalúa su propio reloj de sistema (`SystemClock`). Mantener el polling automático encendido en `application-test.yml` una vez registrado `"task-due-notification"` provocaría que `db-scheduler` consuma esas filas antes de las aserciones. Por ello `src/test/resources/application-test.yml` fija `db-scheduler.enabled: false` (desactivando solo el hilo de polling por defecto, manteniendo intactos los beans `Task<?>` y `JdbcTaskDueScheduleAdapter`), mientras `NotificationSchedulerIntegrationTest` habilita explícitamente `@TestPropertySource(properties = { "db-scheduler.enabled=true", "db-scheduler.polling-interval=200ms" })` para verificar la ejecución real end-to-end contra PostgreSQL/Testcontainers.

---

## 2. Inventario de Archivos Afectados

```
JavaSpringBoot-TaskByTask/
├── src/main/resources/
│   └── application.yml                                                # [MODIFICAR] Añadir app.web-push.{enabled,vapid.*} vía env vars
├── src/test/resources/
│   └── application-test.yml                                           # [MODIFICAR] Fijar db-scheduler.enabled=false y app.web-push.enabled=false
├── src/main/java/com/eyelesscode/task_by_task/
│   ├── shared/
│   │   ├── error/
│   │   │   └── GlobalExceptionHandler.java                            # [MODIFICAR] Manejar MissingServletRequestParameterException -> 400 Solicitud inválida
│   │   └── pagination/
│   │       └── KeysetCursorCodec.java                                 # [REVISAR/AJUSTAR] Asegurar rechazo explícito NotificationCursor null fields
│   ├── task/
│   │   └── TaskRepository.java                                        # [MODIFICAR] Añadir findByIdForDueNotification (JOIN FETCH group, creator, assignedUser)
│   └── notification/
│       ├── PushSubscription.java                                      # [EXISTENTE] Entidad JPA push_subscriptions (se conserva intacta)
│       ├── PushSubscriptionRepository.java                            # [MODIFICAR] Conservar deleteByUserIdAndEndpoint; añadir upsert atómico y consultas
│       ├── NotificationEventType.java                                 # [CREAR] Enum extensivo (TASK_DUE)
│       ├── Notification.java                                          # [CREAR] Entidad JPA tabla `notifications` (ddl-auto=validate)
│       ├── NotificationRepository.java                                # [CREAR] Keyset (created_at DESC, id DESC), unreadCount, read-all, purge < cutoff
│       ├── VapidProperties.java                                       # [CREAR] @ConfigurationProperties(prefix = "app.web-push")
│       ├── VapidConfigurationState.java                               # [CREAR] Validación segura de VAPID/EC P-256 con degradación determinista
│       ├── WebPushConfig.java                                         # [CREAR] @EnableConfigurationProperties(VapidProperties.class) y bean HttpClient
│       ├── WebPushCryptoUtils.java                                    # [CREAR] Validación/decodificación Base64URL, puntos P-256 y escalar privado
│       ├── WebPushPayload.java                                        # [CREAR] DTO JSON del mensaje push (title, body, taskId, groupId, ...)
│       ├── WebPushEncryptionService.java                              # [CREAR] Cifrado RFC 8291 (aes128gcm) + JWT VAPID RFC 8292 (ES256 64B)
│       ├── WebPushTransport.java                                      # [CREAR] Puerto HTTP de envío Web Push + resultado tipado
│       ├── HttpClientWebPushTransport.java                            # [CREAR] Implementación java.net.http.HttpClient con timeouts
│       ├── PushSubscriptionMapper.java                                # [CREAR] PushSubscription / row -> PushSubscriptionResponse
│       ├── PushSubscriptionService.java                               # [CREAR] Clave VAPID, upsert/rebind, delete por ID (404->403) y ?endpoint=
│       ├── NotificationContentFormatter.java                          # [CREAR] Formato español RNF-14 (dd/MM/yyyy), RF-04 (24H/12H) y truncamiento <= 500
│       ├── NotificationMapper.java                                    # [CREAR] Notification -> NotificationResponse
│       ├── NotificationService.java                                   # [CREAR] Centro de notificaciones Keyset, unread-count, read/read-all, delete, purga 30d
│       ├── DueNotificationJobService.java                             # [CREAR] Orquestador TX1 -> HTTP best-effort -> TX2 + detección NTF-REQ-13
│       ├── NotificationSchedulerConfig.java                           # [CREAR] Beans db-scheduler CustomTask (task-due-notification) y RecurringTask (retention)
│       ├── NotificationController.java                                # [CREAR] Endpoints REST /api/v1/notifications/**
│       └── dto/
│           ├── VapidPublicKeyResponse.java                            # [CREAR] record(boolean enabled, @JsonInclude(ALWAYS) String publicKey)
│           ├── RegisterPushSubscriptionRequest.java                   # [CREAR] record con PushSubscriptionKeys, trim() y constraints RFC 8291
│           ├── ValidPushEndpoint.java / ValidPushEndpointValidator    # [CREAR] Constraint URL http/https absoluta <= 2048 chars
│           ├── ValidP256PublicKey.java / ValidP256PublicKeyValidator  # [CREAR] Constraint clave pública EC P-256 65B Base64URL
│           ├── ValidPushAuthSecret.java / ValidPushAuthSecretValidator# [CREAR] Constraint secreto auth 16B Base64URL
│           ├── PushSubscriptionResponse.java                          # [CREAR] record(Long id, String endpoint, Instant createdAt)
│           ├── NotificationResponse.java                              # [CREAR] record(Long id, String eventType, String content, boolean read, Instant createdAt)
│           ├── UnreadCountResponse.java                               # [CREAR] record(long unreadCount)
│           └── MarkAllReadResponse.java                               # [CREAR] record(long updatedCount)
└── src/test/java/com/eyelesscode/task_by_task/
    ├── shared/
    │   ├── error/
    │   │   └── GlobalExceptionHandlerTest.java                        # [MODIFICAR] Verificar MissingServletRequestParameterException -> 400 Solicitud inválida
    │   └── pagination/
    │       └── KeysetCursorCodecTest.java                             # [MODIFICAR] Verificar NotificationCursor con createdAt/id nulos con checksum válido
    └── notification/
        ├── VapidConfigurationStateTest.java                           # [CREAR] Unit: VAPID válido, disabled, parcial (sin private/subject) y llaves inválidas/mismatched
        ├── RegisterPushSubscriptionRequestValidationTest.java         # [CREAR] Unit: validación URL/longitudes/EC P-256/16B auth + unknown fields
        ├── WebPushEncryptionServiceTest.java                          # [CREAR] Unit: cifrado/descifrado real RFC 8291 + verificación firma JWT ES256 RFC 8292
        ├── HttpClientWebPushTransportTest.java                        # [CREAR] Unit/Local HTTP: com.sun.net.httpserver.HttpServer (headers, 201/404/410/500/timeout)
        ├── NotificationContentFormatterTest.java                      # [CREAR] Unit: dd/MM/yyyy, 24H vs 12H, solo-fecha sin 00:00, truncamiento <= 500
        ├── DueNotificationJobServiceTest.java                         # [CREAR] Unit: RNF-20 recipient, NTF-REQ-12/13, pre-09:00 AM, horarios y bordes
        ├── PushSubscriptionIntegrationTest.java                       # [CREAR] IT: VAPID public key, POST 201/200/rebind EC-02, DELETE ID/?endpoint=, auth/logout, CSRF
        ├── NotificationCenterIntegrationTest.java                     # [CREAR] IT: GET Keyset + tie-breakers, unreadOnly, unread-count, read/read-all, DELETE, retención 30d
        ├── DueNotificationDeliveryIntegrationTest.java                # [CREAR] IT: flujo DB+HttpServer (201, 404/410 auto-cleanup EC-01, 5xx EC-07, VAPID off)
        └── NotificationSchedulerIntegrationTest.java                  # [CREAR] IT: db-scheduler enabled=true + polling 200ms ejecutando task-due y registrando retention
```

---

## 3. Decisiones de Diseño y Alternativas Descartadas

| Decisión de Diseño | Opción Elegida | Alternativa Descartada | Justificación Técnica |
| :--- | :--- | :--- | :--- |
| **Handler `db-scheduler` para `task-due-notification`** | `Tasks.custom(TaskDueSchedulePort.TASK_NAME, Void.class)` (`CustomTask<Void>`) | `Tasks.oneTime(...)` (`OneTimeTask<Void>`) | `OneTimeTask` siempre ejecuta `OnCompleteRemove` al finalizar y borraría o fallaría por versión optimista si `NTF-REQ-13` reprograma un vencimiento pospuesto concurrentemente. `CustomTask<Void>` retorna `OnCompleteRemove` en ejecución normal/no-op y `operations.reschedule(...)` cuando `calculateDueTriggerInstant(...) > now + 1s`. |
| **Validación y degradación VAPID (`FND-REQ-34`/`35`, `EC-07`)** | `VapidConfigurationState` tolerante a fallos (desactiva Web Push con log `WARN`/`ERROR` sin abortar arranque) | Lanzar `BeanCreationException` en `@PostConstruct` | El Centro de Notificaciones in-app y toda la API de tareas deben permanecer operativos aunque `VAPID_*` esté ausente, incompleto o mal configurado en un entorno (`NTF-REQ-01`, `NTF-REQ-18`, Riesgo 1 del SVD). |
| **Registro/re-vinculación de `push_subscriptions` (`EC-02`/`03`)** | UPSERT nativo PostgreSQL `ON CONFLICT (endpoint) DO UPDATE ... RETURNING id, created_at, (xmax = 0) AS inserted` | `findByEndpoint` seguido de `save()` en JPA | Elimina condiciones de carrera sobre `endpoint UNIQUE` que envenenan la transacción Hibernate, re-asigna atómicamente `user_id` en navegadores compartidos y distingue inserción (`201`) vs actualización/re-vinculación (`200`) en una sola consulta. |
| **Aislamiento transaccional durante push HTTP** | TX1 (corto: validar tarea, guardar `Notification`, leer suscripciones) -> I/O HTTP fuera de TX -> TX2 (borrar `404`/`410`) | `@Transactional` envolviendo el envío HTTP externo | Evita agotar el pool de conexiones de PostgreSQL/Supabase durante timeouts o latencia de gateways externos (FCM/Mozilla) y garantiza que ningún error HTTP revierta la notificación in-app (`NTF-REQ-15`, `NTF-REQ-18`). |
| **Criptografía Web Push (RFC 8291 + RFC 8292)** | `javax.crypto` / `java.security` + instancia privada `BouncyCastleProvider` (`bcprov-jdk18on`) | Añadir librería externa `nl.martijndwars:web-push` a `pom.xml` | Cumple la restricción de cero cambios en `pom.xml`, evita registrar proveedores JCA globalmente (`Security.addProvider`) y soporta nativamente validación de puntos `secp256r1` + firma `SHA256withPLAIN-ECDSA` (64 bytes IEEE P1363 exactos exigidos por `ES256`). |
| **Aislamiento del scheduler en tests existentes** | `db-scheduler.enabled: false` en `application-test.yml` + `@TestPropertySource(properties = "db-scheduler.enabled=true")` en suite dedicada | Dejar `db-scheduler.enabled: true` en todo `@SpringBootTest` | Los tests de `001`–`003` usan `@MockitoBean Clock` (`2026-11-15`) y filas sintéticas en `scheduled_tasks`; el scheduler con reloj de sistema (`2026-11-18`) consumiría sus filas antes de las aserciones. |
| **Manejo de `?endpoint` ausente en `DELETE` (`NTF-REQ-09`)** | `@RequestParam(required = false) String endpoint` + `BadRequestException` y `@ExceptionHandler(MissingServletRequestParameterException.class)` en `GlobalExceptionHandler` | Dejar que `MissingServletRequestParameterException` caiga en `@ExceptionHandler(Exception.class)` | Sin manejo explícito en `GlobalExceptionHandler` retornaría HTTP 500 en español genérico. Cubrir ambas capas garantiza siempre HTTP `400 Solicitud inválida` con detalle específico. |
| **Visibilidad explícita de `publicKey: null`** | `@JsonInclude(JsonInclude.Include.ALWAYS)` en `VapidPublicKeyResponse.publicKey` | Confiar en defaults de `ObjectMapper` | Garantiza `{ "enabled": false, "publicKey": null }` independientemente de personalizaciones de inclusión `NON_NULL` (`JacksonConfig`). |

---

## 4. Diseño Detallado de Componentes

### 4.1 Configuración, VAPID y Extensiones Compartidas

#### 4.1.1 Configuración en `application.yml` y `application-test.yml`
En `src/main/resources/application.yml`:
```yaml
app:
  web-push:
    enabled: ${WEB_PUSH_ENABLED:false}
    vapid:
      public-key: ${VAPID_PUBLIC_KEY:}
      private-key: ${VAPID_PRIVATE_KEY:}
      subject: ${VAPID_SUBJECT:}
```
En `src/test/resources/application-test.yml`:
```yaml
db-scheduler:
  enabled: false

app:
  web-push:
    enabled: false
```

#### 4.1.2 `WebPushCryptoUtils`, `VapidProperties` y `VapidConfigurationState`
- **`VapidProperties`:**
  ```java
  @ConfigurationProperties(prefix = "app.web-push")
  public record VapidProperties(boolean enabled, VapidKeys vapid) {
      public record VapidKeys(String publicKey, String privateKey, String subject) {}
  }
  ```
- **`WebPushCryptoUtils`:** Clase utilitaria pura respaldada por un `private static final Provider BC_PROVIDER = new BouncyCastleProvider();` y `ECNamedCurveTable.getParameterSpec("secp256r1")`:
  - `decodeBase64UrlOrStandard(String encoded)`: acepta Base64URL (con/sin padding) o Base64 estándar sin caracteres inválidos ni espacios internos; rechaza `null`/blanco/malformado.
  - `decodeP256PublicKey(String encoded)`: exige exactamente 65 bytes (`bytes[0] == 0x04`), decodifica el punto sobre la curva `secp256r1` (`curve.decodePoint(bytes)` + verificación `point.isValid() && !point.isInfinity()`) y construye `ECPublicKey` (JCA).
  - `decodeP256PrivateKey(String encoded)`: exige 32 bytes unsigned big-endian (o tolera representación estándar P-256 `BigInteger d` en rango `[1, n - 1]`) y construye `ECPrivateKey` (JCA).
  - `isKeyPairMatching(ECPrivateKey priv, byte[] publicKeyBytes)`: calcula `Q = G.multiply(d).normalize()` y verifica igualdad constante `Arrays.equals(Q.getEncoded(false), publicKeyBytes)`.
  - `validateAuthSecret(String encoded)`: exige exactamente 16 bytes tras decodificar.
- **`VapidConfigurationState`:** Construido en arranque a partir de `VapidProperties`:
  - Si `!properties.enabled()`: estado inactivo (`active = false`, `publicKey = null`) con `log.info(...)`.
  - Si `vapid == null` o falta alguno de `publicKey`, `privateKey`, `subject` (blanco tras `trim()`): registra `log.warn(...)` sin exponer valores y degrada a `active = false`.
  - Si `subject` no es URI absoluto con esquema `mailto` (con contenido no vacío) o `https` (con host no vacío), o falla `decodeP256PublicKey`, `decodeP256PrivateKey`, o `!isKeyPairMatching(...)`: registra `log.error(...)` (sin imprimir `privateKey`) y degrada a `active = false`.
  - Solo cuando todo es válido: almacena `active = true`, `publicKeyBase64Url` (re-codificada canónicamente sin padding Base64URL), `ecPrivateKey`, `rawPublicKeyBytes` (65B) y `subject`.

#### 4.1.3 Extensiones en `shared/` (`GlobalExceptionHandler` y `KeysetCursorCodec`) y `task/` (`TaskRepository`)
- **`GlobalExceptionHandler`:** Añade `@ExceptionHandler(MissingServletRequestParameterException.class)` devolviendo HTTP `400 Bad Request` con `ProblemDetail` (`title: "Solicitud inválida"`, `detail: "El parámetro '" + ex.getParameterName() + "' es obligatorio y no puede estar vacío"`).
- **`KeysetCursorCodec`:** Asegura que dentro de `case "NOTIFICATION"` cualquier `createdAt == null || id == null` (incluso en tokens manipulados con checksum CRC32 válido) lance `BadRequestException("El cursor de paginación proporcionado no es válido")`, cubriendo `NTF-REQ-20` junto con el rechazo de tokens vacíos/en blanco ya soportado por `decode(...)`.
- **`TaskRepository`:** Añade:
  ```java
  @Query(
          "SELECT t FROM Task t JOIN FETCH t.group g JOIN FETCH g.creator"
                  + " LEFT JOIN FETCH t.assignedUser WHERE t.id = :taskId")
  Optional<Task> findByIdForDueNotification(@Param("taskId") Long taskId);
  ```
  Garantiza lectura en 1 consulta con `assignedUser` y `group.creator` cargados bajo `spring.jpa.open-in-view=false` (`NTF-REQ-14`, `RNF-20`).

---

### 4.2 Gestión de Suscripciones Web Push (`NTF-REQ-01`..`10`, `27`..`29`)

#### 4.2.1 DTOs, Validación y Reglas de Normalización
- **`VapidPublicKeyResponse`:**
  ```java
  public record VapidPublicKeyResponse(
          boolean enabled, @JsonInclude(JsonInclude.Include.ALWAYS) String publicKey) {}
  ```
- **`RegisterPushSubscriptionRequest`:**
  ```java
  public record RegisterPushSubscriptionRequest(
          @NotBlank(message = "El endpoint de suscripción es obligatorio")
                  @Size(max = 2048, message = "El endpoint no puede superar los 2048 caracteres")
                  @ValidPushEndpoint
                  String endpoint,
          @NotNull(message = "Las claves de suscripción son obligatorias") @Valid
                  PushSubscriptionKeys keys) {

      public RegisterPushSubscriptionRequest {
          endpoint = endpoint != null ? endpoint.trim() : null;
      }

      public record PushSubscriptionKeys(
              @NotBlank(message = "La clave pública p256dh es obligatoria")
                      @Size(max = 255, message = "La clave p256dh no puede superar los 255 caracteres")
                      @ValidP256PublicKey
                      String p256dh,
              @NotBlank(message = "El secreto de autenticación auth es obligatorio")
                      @Size(max = 255, message = "El secreto auth no puede superar los 255 caracteres")
                      @ValidPushAuthSecret
                      String auth) {
          public PushSubscriptionKeys {
              p256dh = p256dh != null ? p256dh.trim() : null;
              auth = auth != null ? auth.trim() : null;
          }
      }
  }
  ```
  *Notas clave:*
  - Los validadores `@ValidPushEndpoint`, `@ValidP256PublicKey` y `@ValidPushAuthSecret` retornan `true` cuando el valor es `null` o blanco para no duplicar mensajes de `@NotBlank` en `invalidParams`.
  - `ValidPushEndpointValidator` verifica `URI.create(value)` absoluto con `scheme` `"http"` o `"https"` (`equalsIgnoreCase`) y `host != null && !host.isBlank()`.
  - `ValidP256PublicKeyValidator` invoca `WebPushCryptoUtils.decodeP256PublicKey(value)` (65 bytes + punto válido `secp256r1`).
  - `ValidPushAuthSecretValidator` invoca `WebPushCryptoUtils.validateAuthSecret(value)` (16 bytes).
  - La configuración Jackson (`FAIL_ON_UNKNOWN_PROPERTIES = false`) y la ausencia de campos de servidor en el `record` garantizan que propiedades W3C como `expirationTime` y campos manipulados (`id`, `userId`, `createdAt`) sean ignorados silenciosamente (`NTF-REQ-02`).
- **`PushSubscriptionResponse`:** `record PushSubscriptionResponse(Long id, String endpoint, Instant createdAt)` (omite `p256dh` y `auth`).

#### 4.2.2 `PushSubscriptionRepository` y `PushSubscriptionService`
- **`PushSubscriptionRepository`:** Conserva `void deleteByUserIdAndEndpoint(Long userId, String endpoint)` intacta para `AuthService`, y añade:
  - `Optional<PushSubscription> findByEndpoint(String endpoint);`
  - `@Query("SELECT ps FROM PushSubscription ps JOIN FETCH ps.user WHERE ps.id = :id") Optional<PushSubscription> findByIdWithUser(@Param("id") Long id);`
  - `List<PushSubscription> findAllByUserId(Long userId);`
  - `void deleteAllByIdIn(Collection<Long> ids);`
  - Consulta nativa atómica en interfaz o adaptador JDBC en el mismo repositorio/servicio:
    ```sql
    INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth, created_at)
    VALUES (:userId, :endpoint, :p256dh, :auth, :now)
    ON CONFLICT (endpoint) DO UPDATE
    SET user_id = EXCLUDED.user_id,
        p256dh = EXCLUDED.p256dh,
        auth = EXCLUDED.auth,
        created_at = CASE
            WHEN push_subscriptions.user_id = EXCLUDED.user_id THEN push_subscriptions.created_at
            ELSE EXCLUDED.created_at
        END
    RETURNING id, endpoint, created_at, (xmax = 0) AS inserted
    ```
    Mapeado vía `JdbcTemplate` (con `Timestamp.from(now.truncatedTo(ChronoUnit.MICROS))`) en `PushSubscriptionService.registerOrRebind(...)` devolviendo un resultado interno `(PushSubscriptionResponse response, boolean inserted)` para responder `201 Created` si `inserted == true` o `200 OK` si `inserted == false`.
- **Desuscripción:**
  - `deleteById(Long subscriptionId)` (`NTF-REQ-07`/`08`): `findByIdWithUser(subscriptionId)` -> si vacío `ResourceNotFoundException("Suscripción push no encontrada")` (`404`); si `!sub.getUser().getId().equals(currentUserId)` -> `AccessDeniedException("No tiene permisos para realizar esta acción")` (`403`); en caso contrario `pushSubscriptionRepository.delete(sub)` -> `204`.
  - `deleteByEndpoint(String rawEndpoint)` (`NTF-REQ-09`): `trimmed = rawEndpoint != null ? rawEndpoint.trim() : ""`; si `trimmed.isEmpty()` -> `BadRequestException("El parámetro 'endpoint' es obligatorio y no puede estar vacío")` (`400`); en caso contrario ejecuta `pushSubscriptionRepository.deleteByUserIdAndEndpoint(currentUserId, trimmed)` -> `204` siempre idempotente (nunca toca suscripciones ajenas).

---

### 4.3 Cifrado Web Push (RFC 8291), VAPID JWT (RFC 8292) y Transporte HTTP

- **`WebPushPayload`:**
  `record WebPushPayload(Long notificationId, String eventType, String title, String body, Long taskId, Long groupId, Instant createdAt)`
  Serializado con `tools.jackson.databind.ObjectMapper` (o `com.fasterxml.jackson.databind.ObjectMapper` inyectado con formato ISO UTC), donde `title = "Tarea por vencer"` y `body` es idéntico palabra por palabra a `notifications.content` (`NTF-REQ-16`).
- **`WebPushEncryptionService`:**
  1. `encrypt(byte[] plaintextUtf8, String p256dhEncoded, String authEncoded)`:
     - Decodifica `uaPublic = WebPushCryptoUtils.decodeP256PublicKey(p256dhEncoded)` (65B sin comprimir) y `authSecret` (16B).
     - Genera par efímero `secp256r1` del emisor (`asPrivate`, `asPublicBytes` 65B) y `salt` aleatorio de 16 bytes (`SecureRandom`).
     - ECDH: `KeyAgreement.getInstance("ECDH")` -> `ecdhSecret` (32B).
     - HKDF-SHA256 (`Mac` `HmacSHA256`):
       - `PRK_key = HMAC(salt = authSecret, IKM = ecdhSecret)`
       - `keyInfo = "WebPush: info\0" || uaPublicBytes(65B) || asPublicBytes(65B)`
       - `IKM = HKDF-Expand(PRK_key, keyInfo, 32)`
       - `PRK = HMAC(salt = salt(16B), IKM = IKM)`
       - `CEK = HKDF-Expand(PRK, "Content-Encoding: aes128gcm\0", 16)`
       - `NONCE = HKDF-Expand(PRK, "Content-Encoding: nonce\0", 12)`
     - Registro único RFC 8188: `padded = plaintextUtf8 || 0x02` (validando que `padded.length <= 3994` para caber cómodamente en el récord `rs = 4096`).
     - Cifra con `AES/GCM/NoPadding`, `SecretKeySpec(CEK, "AES")`, `GCMParameterSpec(128, NONCE)`.
     - Ensambla cuerpo binario: `salt(16B) || uint32BE(4096)(4B) || byte(65)(1B) || asPublicBytes(65B) || ciphertextAndTag`.
  2. `createVapidAuthorizationHeader(String endpoint, VapidConfigurationState state, Instant now)`:
     - Extrae `aud` de `URI.create(endpoint)`: `uri.getScheme() + "://" + uri.getHost() + (uri.getPort() != -1 ? ":" + uri.getPort() : "")`.
     - Header JSON Base64URL sin padding: `{"typ":"JWT","alg":"ES256"}`.
     - Payload JSON Base64URL sin padding: `{"aud":"<aud>","exp":<now.getEpochSecond() + 43200>,"sub":"<subject>"}`.
     - Firma `signingInput` (`ASCII` de `headerB64 + "." + payloadB64`) con `Signature.getInstance("SHA256withPLAIN-ECDSA", BC_PROVIDER)` usando `state.privateKey()` -> produce firma exacta de 64 bytes (`r(32B) || s(32B)`).
     - Retorna `"vapid t=" + signingInput + "." + b64Url(sig64) + ", k=" + state.publicKeyBase64Url()`.
- **`WebPushTransport` y `HttpClientWebPushTransport`:**
  - `HttpClient` configurado con `connectTimeout(Duration.ofSeconds(5))` y `requestTimeout(Duration.ofSeconds(10))`.
  - Envía `POST` al `endpoint` con `Content-Encoding: aes128gcm`, `Content-Type: application/octet-stream`, `TTL: 86400`, `Authorization: <vapidHeader>`.
  - Retorna resultado tipado (`DELIVERED_2XX`, `GONE_404_OR_410`, `TRANSIENT_ERROR`) capturando `IOException` / `InterruptedException` (re-marcando `Thread.currentThread().interrupt()` si aplica) sin lanzar excepciones no controladas al scheduler.

---

### 4.4 Entidad `Notification`, Formateo Localizado (`RNF-14`/`RF-04`), Centro de Notificaciones y `db-scheduler`

#### 4.4.1 Entidad `Notification` y `NotificationRepository`
- **`NotificationEventType`:** `public enum NotificationEventType { TASK_DUE }` (extensible para `RF-30` en Posterior).
- **`Notification` (`@Table(name = "notifications")`):**
  - `id`: `@Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long` (`BIGINT`)
  - `user`: `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false) User`
  - `eventType`: `@Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false, length = 50) NotificationEventType`
  - `content`: `@Column(nullable = false, length = 500) String`
  - `read`: `@Column(name = "is_read", nullable = false) boolean`
  - `createdAt`: `@Column(name = "created_at", nullable = false, updatable = false) Instant`
- **`NotificationRepository`:**
  - `@Query("SELECT n FROM Notification n JOIN FETCH n.user WHERE n.id = :id") Optional<Notification> findByIdWithUser(@Param("id") Long id);` (`404` -> `403` bajo `open-in-view=false`)
  - `long countByUserIdAndReadFalse(Long userId);`
  - Keyset primera página (aprovechando `idx_notifications_user_id_created (user_id, created_at DESC, id DESC)`):
    ```java
    @Query("""
        SELECT n FROM Notification n
        WHERE n.user.id = :userId
          AND (:unreadOnly = false OR n.read = false)
        ORDER BY n.createdAt DESC, n.id DESC
        """)
    List<Notification> findFirstPage(
            @Param("userId") Long userId, @Param("unreadOnly") boolean unreadOnly, Pageable pageable);
    ```
  - Keyset página siguiente (`created_at DESC, id DESC`):
    ```java
    @Query("""
        SELECT n FROM Notification n
        WHERE n.user.id = :userId
          AND (:unreadOnly = false OR n.read = false)
          AND (n.createdAt < :cursorCreatedAt
               OR (n.createdAt = :cursorCreatedAt AND n.id < :cursorId))
        ORDER BY n.createdAt DESC, n.id DESC
        """)
    List<Notification> findNextPage(
            @Param("userId") Long userId,
            @Param("unreadOnly") boolean unreadOnly,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);
    ```
  - Marcado masivo (`NTF-REQ-23`):
    `@Modifying(clearAutomatically = true, flushAutomatically = true) @Query("UPDATE Notification n SET n.read = true WHERE n.user.id = :userId AND n.read = false") int markAllAsReadByUserId(@Param("userId") Long userId);`
  - Retención (`NTF-REQ-26`):
    `@Modifying(clearAutomatically = true, flushAutomatically = true) @Query("DELETE FROM Notification n WHERE n.createdAt < :cutoff") int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);`

#### 4.4.2 `NotificationContentFormatter` (`NTF-REQ-15`, `EC-09`)
- Formateadores estáticos thread-safe:
  - `DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)` (`RNF-14`)
  - `TIME_24H_FORMATTER = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)` (`RF-04`)
  - `TIME_12H_FORMATTER = DateTimeFormatter.ofPattern("h:mm a", Locale.US)` (`RF-04`, produce `"2:30 PM"` / `"9:05 AM"` sin dependencias del locale CLDR de JVM)
- Normaliza `title` y `groupName` reemplazando `[\r\n\t]+` por un espacio único y aplicando `.trim()`.
- Si `dueTime == null` (tarea solo-fecha):
  `La tarea "<title>" del grupo "<groupName>" vence el <dd/MM/yyyy>.` (nunca fabrica `00:00` ni `09:00`).
- Si `dueTime != null`: selecciona `TIME_12H_FORMATTER` si `"12H".equalsIgnoreCase(timeFormat)` o `TIME_24H_FORMATTER` por defecto:
  `La tarea "<title>" del grupo "<groupName>" vence el <dd/MM/yyyy> a las <timeFormatted>.`
- Si `formatted.length() > 500`: trunca exactamente a `formatted.substring(0, 497) + "..."` (`VARCHAR(500)`).

#### 4.4.3 `DueNotificationJobService` y `NotificationSchedulerConfig` (`db-scheduler` 15.0.0)
- **`NotificationSchedulerConfig`:**
  - Registra `@Bean CustomTask<Void> taskDueNotificationTask(DueNotificationJobService jobService)`:
    ```java
    return Tasks.custom(TaskDueSchedulePort.TASK_NAME, Void.class)
            .onDeadExecutionRevive()
            .execute((taskInstance, executionContext) -> {
                Optional<Instant> rescheduled = jobService.processDueTaskJob(taskInstance.getId());
                if (rescheduled.isPresent()) {
                    Instant nextInstant = rescheduled.get();
                    return (completion, operations) -> operations.reschedule(completion, nextInstant);
                }
                return new CompletionHandler.OnCompleteRemove<>();
            });
    ```
  - Registra `@Bean RecurringTask<Void> notificationRetentionTask(NotificationService notificationService)`:
    ```java
    return Tasks.recurring(
                    "notification-retention-cleanup",
                    Schedules.daily(ZoneOffset.UTC, LocalTime.of(3, 0)))
            .execute((taskInstance, executionContext) -> notificationService.purgeExpiredNotifications());
    ```
- **`DueNotificationJobService.processDueTaskJob(String taskInstanceId)`:**
  1. Valida prefijo `TaskDueSchedulePort.INSTANCE_PREFIX` (`"task-due-"`) y parsea `Long taskId`; ante formato inválido registra `WARN` y retorna `Optional.empty()` (`NTF-REQ-12`).
  2. Ejecuta **TX1** vía `TransactionTemplate`:
     - Busca `taskRepository.findByIdForDueNotification(taskId)`. Si vacío, `task.isCompleted()` o `task.getDueDate() == null` -> retorna `TxResult.noOp()`.
     - Resuelve destinatario: `User recipient = task.getAssignedUser() != null ? task.getAssignedUser() : task.getGroup().getCreator()` (`NTF-REQ-14`, `RNF-20`).
     - `ZoneId recipientZone = dateTimeProvider.parseZoneId(recipient.getTimezone())`, `Instant now = dateTimeProvider.now().truncatedTo(ChronoUnit.MICROS)`.
     - Recalcula `Optional<Instant> nextTrigger = dateTimeProvider.calculateDueTriggerInstant(task.getDueDate(), task.getDueTime(), recipientZone, now)`.
     - Si `nextTrigger.isPresent() && nextTrigger.get().isAfter(now.plusSeconds(1))` -> retorna `TxResult.reschedule(nextTrigger.get().truncatedTo(ChronoUnit.MICROS))` (`NTF-REQ-13`).
     - Formatea `content` con `NotificationContentFormatter`, persiste y hace `saveAndFlush` de `Notification` (`TASK_DUE`, `read = false`, `createdAt = now`), y lee `List<PushSubscriptionSnapshot>` (`id`, `endpoint`, `p256dh`, `auth`) de `recipient.getId()`.
     - Retorna `TxResult.dispatched(payloadData, subscriptions)`.
  3. Si TX1 indicó `noOp` -> `Optional.empty()`; si indicó `reschedule(instant)` -> `Optional.of(instant)`.
  4. Si TX1 indicó `dispatched`:
     - Si `!vapidConfigurationState.isActive()` o `subscriptions.isEmpty()` -> registra `debug`/`warn` y retorna `Optional.empty()`.
     - Para cada `PushSubscriptionSnapshot`, en bloque `try/catch` aislado: cifra `WebPushPayload` con `WebPushEncryptionService`, firma VAPID `Authorization` y envía vía `WebPushTransport`. Acumula IDs con respuesta `404`/`410`.
     - Si hay IDs `404`/`410`, ejecuta **TX2** vía `TransactionTemplate` eliminando `pushSubscriptionRepository.deleteAllByIdIn(staleIds)`.
     - Retorna `Optional.empty()` sin reintentar ante fallos externos (`NTF-REQ-18`).

---

## 5. Contratos API REST y Precedencia de Errores

Todos los endpoints bajo `/api/v1/notifications/**` exigen sesión autenticada (`401` `No autenticado`) y token CSRF válido en `POST`, `PATCH` y `DELETE` (`403` `Acceso denegado`). Parámetros/cuerpo JSON con tipo o sintaxis inválida retornan HTTP `400` (`title: "Solicitud inválida"`, `detail: "El cuerpo o parámetro de la solicitud no tiene un formato válido"`, `NTF-REQ-29`).

| Método y Ruta | Entrada | Respuesta Exitosa | Errores / Precedencia |
| :--- | :--- | :--- | :--- |
| `GET /api/v1/notifications/push/vapid-public-key` | — | `200 OK`: `{ "enabled": true\|false, "publicKey": "..." \| null }` | `401` sin sesión |
| `POST /api/v1/notifications/push/subscriptions` | `{ "endpoint", "keys": { "p256dh", "auth" } }` (ignora extra W3C/servidor) | `201 Created` (nueva) o `200 OK` (update/re-bind): `PushSubscriptionResponse` | `400` `Error de validación` (`invalidParams`: `endpoint`, `keys`, `keys.p256dh`, `keys.auth`) / `401` / `403` CSRF |
| `DELETE /api/v1/notifications/push/subscriptions/{subscriptionId}` | Path `subscriptionId` | `204 No Content` | `400` ID no numérico -> `404` `"Suscripción push no encontrada"` -> `403` `"No tiene permisos para realizar esta acción"` |
| `DELETE /api/v1/notifications/push/subscriptions` | Query `endpoint` (obligatorio no vacío tras `trim()`) | `204 No Content` (idempotente sobre dueño; no revela ajenas) | `400` `Solicitud inválida` (`"El parámetro 'endpoint' es obligatorio y no puede estar vacío"`) / `401` / `403` CSRF |
| `GET /api/v1/notifications` | Query `cursor` (opc), `limit` (`1..50`, def `20`), `unreadOnly` (def `false`) | `200 OK`: `KeysetPage<NotificationResponse>` (`created_at DESC, id DESC`) | `400` `limit` fuera `[1,50]`, `cursor` blank/inválido/checksum/tipo/`createdAt` o `id` nulo, tipo query inválido |
| `GET /api/v1/notifications/unread-count` | — | `200 OK`: `{ "unreadCount": <long> }` | `401` sin sesión |
| `PATCH /api/v1/notifications/{notificationId}/read` | Path `notificationId` | `200 OK`: `NotificationResponse` (`read: true`, idempotente) | `400` ID no numérico -> `404` `"Notificación no encontrada"` -> `403` `"No tiene permisos para realizar esta acción"` |
| `PATCH /api/v1/notifications/read-all` | — | `200 OK`: `{ "updatedCount": <long> }` | `401` / `403` CSRF |
| `DELETE /api/v1/notifications/{notificationId}` | Path `notificationId` | `204 No Content` | `400` ID no numérico -> `404` `"Notificación no encontrada"` -> `403` `"No tiene permisos para realizar esta acción"` |

---

## 6. Estrategia de Pruebas Automatizadas

1. **Pruebas Unitarias Puras:**
   - **`KeysetCursorCodecTest`:** ampliar con `NotificationCursor` rechazando `createdAt == null` o `id == null` tanto en constructor como en tokens firmados con checksum CRC32 válido (`NTF-REQ-20`).
   - **`GlobalExceptionHandlerTest`:** verificar `MissingServletRequestParameterException` -> `400 Solicitud inválida` (`NTF-REQ-09`).
   - **`VapidConfigurationStateTest`:** validar generación/carga de par P-256 real, `enabled = false`, configuración parcial (faltante `publicKey`, `privateKey` o `subject`), `subject` no `mailto`/`https`, y par de claves malformadas o no emparejadas (`NTF-REQ-01`, `EC-07`).
   - **`RegisterPushSubscriptionRequestValidationTest`:** validar URL `http`/`https` absoluta <= 2048, longitud <= 255 de claves, punto EC P-256 inválido/fuera de curva, secreto `auth` != 16 bytes, `trim()` e ignorado de campos W3C `expirationTime` (`NTF-REQ-06`).
   - **`WebPushEncryptionServiceTest`:** cifrar payload JSON con clave efímera y verificar **descifrado inverso completo** con la clave privada del cliente (RFC 8291 `aes128gcm`), además de validar la firma JWT `ES256` RFC 8292 con la clave pública VAPID (`NTF-REQ-16`).
   - **`HttpClientWebPushTransportTest`:** usar `com.sun.net.httpserver.HttpServer` efímero local para verificar método `POST`, encabezados `Authorization`, `Content-Encoding: aes128gcm`, `TTL`, lectura de códigos `201`, `404`, `410`, `500` y manejo seguro de timeouts (`NTF-REQ-16..18`).
   - **`NotificationContentFormatterTest`:** verificar `dd/MM/yyyy`, formato `24H` vs `12H`, omisión de hora en tareas solo-fecha sin `00:00` ficticio, limpieza de saltos de línea y truncamiento exacto `<= 500` caracteres (`NTF-REQ-15`, `EC-09`).
   - **`DueNotificationJobServiceTest`:** verificar resolución `RNF-20` (`assignedUser` vs `group.creator`), `no-op` (`NTF-REQ-12`), y detección `NTF-REQ-13` con `Clock` fijo (pospuesta a día futuro, `dueDate == today(zone)` antes de `09:00:00` locales tras cambio de zona o limpieza de `dueTime`, vs vencida hace 5s que sí notifica).
2. **Pruebas de Integración `@SpringBootTest` + `Testcontainers` (`postgres:alpine`, `ddl-auto=validate`):**
   - **`PushSubscriptionIntegrationTest`:** `GET /push/vapid-public-key` (deshabilitado vs habilitado vía `@TestPropertySource`), `POST /push/subscriptions` (`201` nueva, `200` rotación idempotente `EC-03`, multi-dispositivo `NTF-REQ-05`, re-vinculación entre usuarios en navegador compartido `EC-02`), `DELETE /{id}` (`404`->`403`->`204`), `DELETE ?endpoint=` (idempotente/privacidad/`400` faltante o vacío `EC-08`), regresión `POST /api/v1/auth/logout` con `endpoint` (`EC-12`), `401` y CSRF `403`.
   - **`NotificationCenterIntegrationTest`:** listado `GET /api/v1/notifications` multi-página con `created_at DESC, id DESC` y desempate `id`, estabilidad Keyset bajo nuevas inserciones (`EC-10`), `unreadOnly`, `unread-count`, `PATCH /{id}/read` idempotente, `PATCH /read-all`, `DELETE /{id}`, precedencia `404`->`403`, errores `400` (cursor inválido/blank/null-fields, `limit`, tipo path/query inválido `NTF-REQ-29`), y `purgeExpiredNotifications()` verificando frontera exacta de 30 días calendario (`EC-11`).
   - **`DueNotificationDeliveryIntegrationTest`:** `Testcontainers` + `HttpServer` local + claves VAPID P-256 generadas dinámicamente en `@DynamicPropertySource` / `@TestPropertySource`: comprueba persistencia in-app + descifrado real de los bytes recibidos por `HttpServer`, auto-limpieza de endpoint `410`/`404` conservando otro dispositivo (`EC-01`), resiliencia ante `500`/`429`/timeout sin duplicar ni revertir `notifications` (`EC-07`), y envío con VAPID deshabilitado/parcial.
   - **`NotificationSchedulerIntegrationTest`:** contexto aislado con `@TestPropertySource(properties = { "db-scheduler.enabled=true", "db-scheduler.polling-interval=200ms" })` + `Awaitility`: inserta job vía `TaskDueSchedulePort` (simulando `003-tasks-and-tags`) y comprueba que el `Scheduler` real de `db-scheduler` toma y ejecuta `task-due-notification` (tanto envío real como reprogramación `NTF-REQ-13`), además de registrar y ejecutar `"notification-retention-cleanup"` sin errores de PostgreSQL.

---

## 7. Matriz de Cobertura de Requerimientos

| Requisito EARS / Caso Borde | Componente / Archivo de Implementación | Clase de Prueba de Verificación |
| :--- | :--- | :--- |
| **NTF-REQ-01** | `VapidProperties`, `WebPushConfig`, `VapidConfigurationState`, `PushSubscriptionService`, `NotificationController` | `VapidConfigurationStateTest`, `PushSubscriptionIntegrationTest` |
| **NTF-REQ-02** a **06** | `RegisterPushSubscriptionRequest`, `WebPushCryptoUtils`, `@ValidPush*`, `PushSubscriptionRepository` (UPSERT nativo), `PushSubscriptionService` | `RegisterPushSubscriptionRequestValidationTest`, `PushSubscriptionIntegrationTest` |
| **NTF-REQ-07** a **10** | `PushSubscriptionService`, `NotificationController`, `PushSubscriptionRepository`, `AuthService` (regresión) | `PushSubscriptionIntegrationTest`, `AuthLoginLogoutIntegrationTest`, `UserAccountDeletionIntegrationTest` |
| **NTF-REQ-11** a **14** | `NotificationSchedulerConfig` (`CustomTask`), `DueNotificationJobService`, `TaskRepository.findByIdForDueNotification`, `DateTimeProvider` | `DueNotificationJobServiceTest`, `NotificationSchedulerIntegrationTest` |
| **NTF-REQ-15** | `Notification`, `NotificationEventType`, `NotificationContentFormatter`, `DueNotificationJobService` (TX1) | `NotificationContentFormatterTest`, `DueNotificationDeliveryIntegrationTest` |
| **NTF-REQ-16** a **18** | `WebPushEncryptionService`, `WebPushTransport`, `HttpClientWebPushTransport`, `DueNotificationJobService` (HTTP + TX2) | `WebPushEncryptionServiceTest`, `HttpClientWebPushTransportTest`, `DueNotificationDeliveryIntegrationTest` |
| **NTF-REQ-19** a **25** | `NotificationRepository`, `NotificationMapper`, `NotificationService`, `NotificationController`, `KeysetCursorCodec` | `KeysetCursorCodecTest`, `NotificationCenterIntegrationTest` |
| **NTF-REQ-26** | `NotificationRepository.deleteByCreatedAtBefore`, `NotificationService.purgeExpiredNotifications`, `NotificationSchedulerConfig` (`RecurringTask`) | `NotificationCenterIntegrationTest`, `NotificationSchedulerIntegrationTest` |
| **NTF-REQ-27** a **29** | `SecurityConfig`, `GlobalExceptionHandler` (`MissingServletRequestParameterException`), validadores DTO | `GlobalExceptionHandlerTest`, `PushSubscriptionIntegrationTest`, `NotificationCenterIntegrationTest` |
| **EC-01** a **EC-12** | Validadores Web Push, UPSERT `endpoint`, `CustomTask`, `NotificationContentFormatter`, `NotificationRepository`, `VapidConfigurationState` | Todas las suites unitarias y `*IntegrationTest` de `notification/` |
