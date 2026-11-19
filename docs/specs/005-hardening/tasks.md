# Lista de Tareas: 005-hardening (Endurecimiento de Producción, Contrato OpenAPI y Verificación Final del MVP)

**Fase:** 005-hardening  
**Estado:** draft  
**Fecha:** 2026-11-18  
**Autor:** SDD Planner Agent  
**Referencias:**
- `docs/specs/005-hardening/spec.md` (HRD-REQ-01 a HRD-REQ-23, EC-01 a EC-08)
- `docs/specs/005-hardening/plan.md` (Diseño técnico y arquitectura)
- `docs/constitution.md` (Principios 1 a 6)
- `AGENTS.md` (Convenciones Pure Java 21, verificación Spotless y Testcontainers)
- `src/main/resources/db/migration/V1__init_schema.sql` (Esquema inmutable)

---

## Tareas de Implementación

- [x] **T1: Configuración de perfiles productivos, HikariCP, Actuator endurecido, apagado elegante y plantilla `.env.example`**
  - **Requisitos cubiertos:** HRD-REQ-12, HRD-REQ-13, HRD-REQ-14, HRD-REQ-15, HRD-REQ-16, HRD-REQ-20, FND-REQ-34, FND-REQ-35.
  - **Archivos a crear/modificar:**
    - `.env.example`
    - `src/main/resources/application.yml`
    - `src/main/resources/application-dev.yml`
    - `src/main/resources/application-prod.yml`
    - `src/test/resources/application-test.yml`
    - `src/main/java/com/eyelesscode/task_by_task/shared/security/SecurityProperties.java`
    - `src/main/java/com/eyelesscode/task_by_task/TaskByTaskApplication.java` (o `@EnableConfigurationProperties` en config de seguridad)
  - **Descripción:**
    - Crear `SecurityProperties` (`@ConfigurationProperties(prefix = "app.security")`) con sub-records validados para `Cors(allowedOrigins, maxAgeSeconds)` (prohibiendo `*` al arranque), `Cookie(secure, sameSite, domain)` (validando `Lax`/`Strict`/`None` y exigiendo `secure=true` cuando `sameSite=None`), y `trustedProxies`.
    - Configurar `application.yml` con defaults seguros de producción (`logging.level.com.eyelesscode.task_by_task: INFO`, `server.forward-headers-strategy: none`, `server.shutdown: graceful`, `spring.lifecycle.timeout-per-shutdown-phase: 25s`, `server.error.include-*` desactivados, `server.compression.enabled: true`, pool HikariCP acotado con `maximum-pool-size: ${DB_POOL_MAX_SIZE:10}`, `minimum-idle: ${DB_POOL_MIN_IDLE:2}`, `connection-timeout: 5000`, `idle-timeout: 300000`, `max-lifetime: 1500000`, `open-in-view: false`), y Actuator endurecido (`management.endpoints.access.default: none`, `management.endpoints.web.exposure.include: health`, `management.endpoint.health.access: read-only`, `show-details: never`, `probes.enabled: true`, `livenessstate`/`readinessstate` habilitados, `readiness` con `readinessState,db`).
    - Mantener logs `DEBUG`/SQL, `cookie.secure: false`, CORS localhost y `springdoc` activos en `application-dev.yml`; configurar `application-prod.yml` con `cookie.secure: ${COOKIE_SECURE:true}`, `springdoc` deshabilitado por defecto (`${OPENAPI_ENABLED:false}` / `${SWAGGER_UI_ENABLED:false}`) y `INFO`; ajustar `application-test.yml` manteniendo `db-scheduler.enabled: false`, `cookie.secure: false`, origen CORS de prueba y `springdoc` habilitado para tests de contrato.
    - Crear `.env.example` documentando todas las variables de entorno (incluyendo nota de puerto `5432` Session Mode de Supabase para Flyway) sin ninguna credencial real.
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test -Dtest=TaskByTaskApplicationTests,ApplicationIntegrationTest` pasan sin errores y las propiedades de `SecurityProperties` validan correctamente restricciones inválidas.

- [ ] **T2: Frontera de proxy de confianza (`TrustedProxyResolver`, `TrustedForwardedHeaderFilter`) y protección anti-spoofing en `RateLimitService`**
  - **Requisitos cubiertos:** HRD-REQ-03, EC-02, RNF-06, RNF-08.
  - **Archivos a crear/modificar:**
    - `src/main/java/com/eyelesscode/task_by_task/shared/security/TrustedProxyResolver.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/security/TrustedForwardedHeaderFilter.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/ratelimit/RateLimitService.java`
    - `src/test/java/com/eyelesscode/task_by_task/shared/security/TrustedProxyResolverTest.java`
    - `src/test/java/com/eyelesscode/task_by_task/shared/security/TrustedForwardedHeaderFilterTest.java`
    - `src/test/java/com/eyelesscode/task_by_task/shared/ratelimit/RateLimitServiceTest.java`
  - **Descripción:**
    - Implementar `TrustedProxyResolver` con matching CIDR puro IPv4/IPv6 (incluyendo IPv4-mapped IPv6) sobre `app.security.trusted-proxies`.
    - Implementar `TrustedForwardedHeaderFilter` (`OncePerRequestFilter` con `@Order(Ordered.HIGHEST_PRECEDENCE)`) que delega en `ForwardedHeaderFilter` únicamente cuando `remoteAddr` es un proxy confiable y elimina `Forwarded`, `X-Forwarded-*` y `X-Real-IP` cuando `remoteAddr` es una IP pública no confiable.
    - Actualizar `RateLimitService` para inyectar `TrustedProxyResolver` (conservando constructor por defecto para tests unitarios con defaults confiables) de modo que `resolveClientIp` solo lea `X-Forwarded-For`/`X-Real-IP` si `isTrustedProxy(request.getRemoteAddr())` es `true`, ignorando cabeceras falsificadas desde IPs públicas.
    - Crear `TrustedProxyResolverTest`, `TrustedForwardedHeaderFilterTest` y ampliar `RateLimitServiceTest` verificando preservación tras proxy privado (`10.0.0.99`/`127.0.0.1`) y bloqueo de suplantación desde IPs públicas (`203.0.113.77`).
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test -Dtest=TrustedProxyResolverTest,TrustedForwardedHeaderFilterTest,RateLimitServiceTest` pasan al 100%.

- [ ] **T3: Endurecimiento HTTP en `SecurityConfig`, CORS, cabeceras defensivas, bootstrap `GET /api/v1/auth/csrf`, política de cookies y `upgradeEncoding` en login**
  - **Requisitos cubiertos:** HRD-REQ-01, HRD-REQ-02, HRD-REQ-04, HRD-REQ-05, HRD-REQ-06, HRD-REQ-12, HRD-REQ-13, HRD-REQ-20, EC-01, EC-02, EC-04, EC-07.
  - **Archivos a crear/modificar:**
    - `src/main/java/com/eyelesscode/task_by_task/auth/dto/CsrfTokenResponse.java`
    - `src/main/java/com/eyelesscode/task_by_task/auth/AuthController.java`
    - `src/main/java/com/eyelesscode/task_by_task/auth/AuthService.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/security/SecurityConfig.java`
    - `src/test/java/com/eyelesscode/task_by_task/auth/AuthLoginLogoutIntegrationTest.java`
    - `src/test/java/com/eyelesscode/task_by_task/shared/security/SecurityIntegrationTest.java`
    - `src/test/java/com/eyelesscode/task_by_task/shared/security/HardeningSecurityIntegrationTest.java`
  - **Descripción:**
    - Añadir `CsrfTokenResponse` y `GET /api/v1/auth/csrf` en `AuthController`.
    - Implementar `passwordEncoder.upgradeEncoding(user.getPasswordHash())` dentro de `AuthService.login(...)` para re-hashear transparentemente contraseñas cuyo algoritmo requiera actualización.
    - Actualizar `SecurityConfig`:
      - Permitir `GET /api/v1/auth/csrf`, `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, rutas públicas de auth y rutas Swagger/OpenAPI (para que cuando `springdoc` esté deshabilitado devuelvan `404` RFC 7807 en español); denegar cualquier otro `/actuator/**` (`.denyAll()`).
      - Registrar `CorsConfigurationSource` desde `SecurityProperties.cors()` (`allowCredentials = true`, métodos `GET, POST, PATCH, PUT, DELETE, OPTIONS`, cabeceras `Content-Type, Accept, X-XSRF-TOKEN, X-Requested-With`, `exposedHeaders = Retry-After`).
      - Configurar cabeceras de seguridad (`nosniff`, `DENY`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=()`, `HSTS`, y `DelegatingRequestMatcherHeaderWriter` para `Content-Security-Policy` estricta en API vs CSP acotada en `/swagger-ui/**` cuando Swagger esté habilitado).
      - Unificar `DefaultCookieSerializer` (`SESSION`) y `CookieCsrfTokenRepository` (`XSRF-TOKEN`) con `SecurityProperties.cookie()` (`SameSite`, `Domain` opcional, y `Secure` forzado cuando `cookie.secure=true` o resuelto vía `request.isSecure()` cuando `cookie.secure=false`).
    - Añadir pruebas en `AuthLoginLogoutIntegrationTest` (re-hashing transparente y `GET /api/v1/auth/csrf`) y crear `HardeningSecurityIntegrationTest` verificando CORS, cabeceras, CSP, cookies tras proxy HTTPS confiable vs falso, Actuator liveness/readiness/denegación.
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test -Dtest=SecurityIntegrationTest,HardeningSecurityIntegrationTest,AuthLoginLogoutIntegrationTest,AuthRegistrationIntegrationTest,AuthPasswordRecoveryIntegrationTest` pasan al 100%.

- [ ] **T4: Deserialización JSON estricta en Jackson 3 (`JacksonConfig` + DTOs) y completitud del contrato RFC 7807 en borde HTTP (`GlobalExceptionHandler`)**
  - **Requisitos cubiertos:** HRD-REQ-07, HRD-REQ-08, HRD-REQ-09, HRD-REQ-10, HRD-REQ-11, EC-03, EC-07.
  - **Archivos a crear/modificar:**
    - `src/main/java/com/eyelesscode/task_by_task/shared/config/JacksonConfig.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/security/RestAuthenticationEntryPoint.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/security/RestAccessDeniedHandler.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/error/GlobalExceptionHandler.java`
    - `src/main/java/com/eyelesscode/task_by_task/auth/dto/{RegisterRequest,LoginRequest,LogoutRequest,RecoverPasswordRequest}.java`
    - `src/main/java/com/eyelesscode/task_by_task/user/dto/{UpdateProfileRequest,ChangePasswordRequest,RegenerateRecoveryCodesRequest,DeleteAccountRequest}.java`
    - `src/main/java/com/eyelesscode/task_by_task/group/dto/{CreateGroupRequest,UpdateGroupRequest}.java`
    - `src/main/java/com/eyelesscode/task_by_task/tag/dto/CreateTagRequest.java`
    - `src/test/java/com/eyelesscode/task_by_task/shared/config/JacksonStrictCoercionTest.java`
    - `src/test/java/com/eyelesscode/task_by_task/shared/error/GlobalExceptionHandlerTest.java`
    - `src/test/java/com/eyelesscode/task_by_task/shared/security/HardeningSecurityIntegrationTest.java`
  - **Descripción:**
    - Añadir `@Bean JsonMapperBuilderCustomizer` en `JacksonConfig` deshabilitando `FAIL_ON_UNKNOWN_PROPERTIES` y aplicando `CoercionAction.Fail` en `LogicalType.Textual`, `Boolean`, `Integer` y `DateTime`. Añadir `@JsonIgnoreProperties(ignoreUnknown = true)` en todos los DTOs de entrada restantes y migrar `RestAuthenticationEntryPoint` / `RestAccessDeniedHandler` a `tools.jackson.databind.ObjectMapper`.
    - Añadir handlers en `GlobalExceptionHandler` para `NoResourceFoundException`/`NoHandlerFoundException` (`404 Recurso no encontrado`), `HttpRequestMethodNotSupportedException` (`405 Método no permitido` con cabecera `Allow`), `HttpMediaTypeNotSupportedException` (`415 Tipo de medio no soportado`), `HttpMediaTypeNotAcceptableException` (`406 Formato no aceptable` con `Content-Type: application/problem+json`) y `ServletRequestBindingException` (`400 Solicitud inválida`).
    - Escribir `JacksonStrictCoercionTest`, ampliar `GlobalExceptionHandlerTest` y verificar en `HardeningSecurityIntegrationTest` la precedencia `401` anónimo vs `404`/`405`/`415`/`406` autenticado y rechazo uniforme de coerción escalar e ignorado de desconocidos en los 6 módulos.
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test -Dtest=JacksonStrictCoercionTest,GlobalExceptionHandlerTest,HardeningSecurityIntegrationTest,UpdateTaskRequestDeserializerTest` pasan al 100%.

- [ ] **T5: Pulido integral del contrato OpenAPI 3 (`OpenApiConfig`, 6 controladores, DTOs) y verificación de perfil `prod`**
  - **Requisitos cubiertos:** HRD-REQ-14, HRD-REQ-17, HRD-REQ-18, HRD-REQ-19, HRD-REQ-20, HRD-REQ-22, EC-04, EC-05, EC-06.
  - **Archivos a crear/modificar:**
    - `src/main/java/com/eyelesscode/task_by_task/shared/config/OpenApiConfig.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/error/ValidationErrorDetail.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/pagination/KeysetPage.java`
    - `src/main/java/com/eyelesscode/task_by_task/auth/AuthController.java` y `auth/dto/*.java`
    - `src/main/java/com/eyelesscode/task_by_task/user/UserController.java` y `user/dto/*.java`
    - `src/main/java/com/eyelesscode/task_by_task/group/GroupController.java` y `group/dto/*.java`
    - `src/main/java/com/eyelesscode/task_by_task/tag/TagController.java` y `tag/dto/*.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/TaskController.java` y `task/dto/*.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/NotificationController.java` y `notification/dto/*.java`
    - `src/test/java/com/eyelesscode/task_by_task/shared/security/OpenApiContractIntegrationTest.java`
    - `src/test/java/com/eyelesscode/task_by_task/shared/security/ProdProfileIntegrationTest.java`
  - **Descripción:**
    - Crear `OpenApiConfig` (`@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true", matchIfMissing = true)`) con metadatos en español, `securitySchemes` (`sessionCookie` + `csrfToken`) y `OpenApiCustomizer` para registrar esquemas RFC 7807 (`ProblemDetailResponse`), limpiar esquemas internos `PatchField*` y publicar el esquema plano exacto de `UpdateTaskRequest` (`title` no anulable 2–100; `description`/`dueDate`/`dueTime`/`tags` anulables).
    - Completar `@Tag`, `@Operation`, `@Parameter`, `@ApiResponses` y `@Schema` (límites del Apéndice B, formatos `yyyy-MM-dd`, `HH:mm[:ss]`, `24H`/`12H`, `ZoneId`, `PENDING`/`COMPLETED`/`ALL`, códigos `200`/`201`/`204`/`400`/`401`/`403`/`404`/`409`/`429`) en los 6 controladores y todos sus DTOs.
    - Crear `OpenApiContractIntegrationTest` verificando `/v3/api-docs` completo, esquema plano de `PATCH /api/v1/tasks/{taskId}` y ausencia total de rutas/conceptos Posteriores (`RF-08`..`RF-11`, `RF-17`..`RF-21`, `RF-23`, `RF-26`, `RF-30`..`RF-33`) o excluidos; y crear `ProdProfileIntegrationTest` validando perfil `prod` (pool HikariCP acotado, `springdoc` deshabilitado devolviendo `404` RFC 7807 en español, `/actuator/health/readiness` `200 UP`, y VAPID off sin degradar salud).
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test -Dtest=OpenApiContractIntegrationTest,ProdProfileIntegrationTest,ApplicationIntegrationTest` pasan al 100%.

- [ ] **T6: Suite End-to-End (E2E) integral multi-módulo del MVP sobre PostgreSQL real (`MvpEndToEndJourneyIntegrationTest`)**
  - **Requisitos cubiertos:** HRD-REQ-21, HRD-REQ-22, EC-08, OBJ-01 a OBJ-03, RF-01 a RF-07, RF-12 a RF-16, RF-22, RF-24, RF-25, RF-27 a RF-29.
  - **Archivos a crear:**
    - `src/test/java/com/eyelesscode/task_by_task/mvp/MvpEndToEndJourneyIntegrationTest.java`
  - **Descripción:**
    - Implementar un test E2E de ciclo de vida completo con `@SpringBootTest`, `Testcontainers` (`postgres:alpine`), `com.sun.net.httpserver.HttpServer` embebido para entrega Web Push real, par de claves VAPID P-256 válidas y `db-scheduler.enabled=true` (`@DirtiesContext(AFTER_CLASS)`):
      1. `GET /api/v1/auth/csrf` -> `POST /api/v1/auth/register` (8 códigos, grupo `"Personal"`, `SESSION` + `XSRF-TOKEN`).
      2. `POST /api/v1/auth/login` con actualización de zona horaria -> `PATCH /api/v1/users/me` (preferencia `12H`/`24H`).
      3. `POST /api/v1/groups`, `GET /api/v1/groups` Keyset (`"Personal"` primero), `PATCH /api/v1/groups/{id}`, intento de borrado de `"Personal"` (`409`).
      4. `POST /api/v1/groups/{id}/tasks` (solo fecha `09:00 AM`/`23:59:59.999`, fecha+hora y sin fecha) con etiquetas case-insensitive, `PATCH /api/v1/tasks/{id}`, `GET /api/v1/tasks` con filtros combinados y paginación Keyset, `POST /move` y `POST /copy`, desasociación vs eliminación de etiqueta de grupo, `PATCH /completion`.
      5. `GET /vapid-public-key`, registro y re-vinculación de suscripciones Web Push (incluyendo un endpoint que devuelve `410 Gone` y otro `201`), disparo automático en `db-scheduler` generando `TASK_DUE` in-app y push cifrado RFC 8291/VAPID RFC 8292 + limpieza automática del `410`, consulta Keyset/unread-count/mark-read/read-all/delete en `/api/v1/notifications`, y ejecución de retención > 30 días.
      6. `POST /api/v1/auth/logout` selectivo por `endpoint`, `POST /api/v1/auth/recover-password` con código de un solo uso (invalidación global de sesiones y rechazo de reuso), re-login y `DELETE /api/v1/users/me` verificando 0 filas huérfanas en las 12 tablas de `V1__init_schema.sql`.
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test -Dtest=MvpEndToEndJourneyIntegrationTest,NotificationSchedulerIntegrationTest` pasan al 100%.

- [ ] **T7: Auditoría final de alcance MVP, formato Spotless, regresión completa del proyecto y cierre documental (`MEMORY.md`)**
  - **Requisitos cubiertos:** HRD-REQ-22, HRD-REQ-23, Constitución Principios 1 a 6.
  - **Archivos a modificar/verificar:**
    - `docs/specs/005-hardening/plan.md` (`**Estado:** completed`)
    - `docs/specs/005-hardening/tasks.md` (`**Estado:** completed` y todas las casillas `[x]`)
    - `MEMORY.md`
    - Todo el repositorio.
  - **Descripción:**
    - Verificar ausencia de secretos en el árbol versionado (`git status`/grep de credenciales), ausencia de nuevas migraciones SQL o cambios no autorizados en `pom.xml`/`docs/TaskByTask-SRS.md`/`docs/TaskByTask-SVD.md`, y ausencia de funciones Posteriores o excluidas.
    - Ejecutar `./mvnw spotless:apply` seguido de `./mvnw spotless:check compile test-compile`.
    - Ejecutar `./mvnw test` verificando que el 100% de las pruebas unitarias y de integración (`000` a `005`) pasen con 0 fallos, 0 errores y 0 pruebas omitidas.
    - Actualizar `MEMORY.md` reflejando el cierre de `005-hardening`, decisiones finales de endurecimiento (`TrustedProxyResolver`, `GET /api/v1/auth/csrf`, Jackson 3 `JsonMapperBuilderCustomizer`, OpenAPI `3.2.0` + `application-prod.yml`), el conteo final de pruebas y estado MVP completo.
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test` finalizan con éxito (`BUILD SUCCESS`) y `MEMORY.md`, `plan.md` y `tasks.md` reflejan el cierre completo.
