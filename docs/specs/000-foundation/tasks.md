# Lista de Tareas: 000-foundation (Base Arquitectural y de Persistencia)

**Fase:** Foundation  
**Estado:** completed (todas las tareas T1 a T11 completadas exitosamente)  
**Fecha:** 2026-11-09  
**Autor:** SDD Planner Agent  
**Referencias:**
- `docs/specs/000-foundation/spec.md` (FND-REQ-01 a FND-REQ-37)
- `docs/specs/000-foundation/plan.md` (Diseño técnico y arquitectura)
- `docs/constitution.md` (Principios 1 a 6)
- `AGENTS.md` (Comandos de verificación y domain rules)

---

## Tareas de Implementación

- [x] **T1: Configuración de dependencias en `pom.xml` y plugin `spotless-maven-plugin`**
  - **Requisitos cubiertos:** FND-REQ-01 a FND-REQ-37, Constitución Principio 6.
  - **Archivos a modificar:** `pom.xml`.
  - **Descripción:** Agregar todas las dependencias requeridas del stack: Spring Boot starters (web, data-jpa, security, validation, actuator, test), PostgreSQL driver, Flyway (core + postgresql), Spring Session JDBC, `db-scheduler`, Bucket4j + Caffeine, BouncyCastle, SpringDoc OpenAPI, Testcontainers PostgreSQL + JUnit Jupiter y Awaitility. Configurar `spotless-maven-plugin` con formato Java estándar (google-java-format o palantir, 4 espacios, remoción de imports no utilizados).
  - **Criterio de finalización (Done when):** `./mvnw clean spotless:check compile test-compile` finaliza exitosamente sin errores de dependencias ni de formato.

- [x] **T2: Configuración de propiedades de la aplicación y exclusión de secretos en `.gitignore`**
  - **Requisitos cubiertos:** FND-REQ-34, FND-REQ-35, Constitución Principio 5.
  - **Archivos a crear/modificar:** `.gitignore`, `src/main/resources/application.yml`, `src/main/resources/application-dev.yml`, `src/test/resources/application-test.yml`, eliminar `application.properties`.
  - **Descripción:**
    - Agregar `.env`, `.env.*` y `application-local.yml` a `.gitignore`.
    - Configurar en `application.yml` los parámetros base: datasource con variables de entorno (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`), `spring.jpa.hibernate.ddl-auto=validate`, `spring.session.store-type=jdbc`, configuración de `db-scheduler` y logging.
    - Configurar en `application-dev.yml` los defaults para conexión local hacia Supabase Session Mode (puerto `5432`).
    - Configurar en `application-test.yml` el perfil `test` con `ddl-auto=validate` y configuraciones adecuadas para ejecución en contenedor.
  - **Criterio de finalización (Done when):** Archivo `application.properties` eliminado, `.gitignore` contiene las exclusiones de secretos, y `./mvnw compile` compila limpiamente.

- [x] **T3: Creación de la migración base de persistencia `V1__init_schema.sql`**
  - **Requisitos cubiertos:** FND-REQ-01 a FND-REQ-08, FND-REQ-24, FND-REQ-25, RNF-20, RF-06, RF-07, RF-12, RF-15, RF-22, RF-29.
  - **Archivos a crear:** `src/main/resources/db/migration/V1__init_schema.sql`.
  - **Descripción:** Escribir el DDL completo para PostgreSQL:
    - Tablas de dominio: `users`, `recovery_codes`, `groups`, `group_memberships` (soporte extensible de roles), `tags`, `tasks`, `task_tags`, `push_subscriptions`, `notifications`.
    - Tablas de infraestructura: `SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES`, `scheduled_tasks`.
    - Restricciones e índices: check constraint `due_time IS NULL OR due_date IS NOT NULL`, índice único parcial `uq_groups_creator_personal` para garantizar un solo grupo personal por usuario, índice normalizado para tags `UNIQUE (group_id, TRIM(LOWER(name)))`, e índice compuesto de ordenamiento para tareas `idx_tasks_keyset_order`.
  - **Criterio de finalización (Done when):** El archivo SQL está creado en `src/main/resources/db/migration/V1__init_schema.sql`, con sintaxis válida de PostgreSQL y todas las tablas, restricciones e índices definidos en la sección 4 de `plan.md`.

- [x] **T4: Arnés de pruebas de integración con Testcontainers y Smoke Test de arranque**
  - **Requisitos cubiertos:** FND-REQ-36, FND-REQ-37, Constitución Principio 6.
  - **Archivos a crear:** `src/test/java/com/eyelesscode/task_by_task/shared/AbstractIntegrationTest.java`, `src/test/java/com/eyelesscode/task_by_task/shared/ApplicationIntegrationTest.java`.
  - **Descripción:**
    - Crear `AbstractIntegrationTest` configurando `PostgreSQLContainer<?>` con imagen `postgres:alpine` como contenedor singleton reutilizable con `@ServiceConnection` o `@DynamicPropertySource`.
    - Crear `ApplicationIntegrationTest` extendiendo de `AbstractIntegrationTest`, verificando que el contexto de Spring Boot inicia, Flyway ejecuta exitosamente `V1__init_schema.sql` y Hibernate valida el esquema (`ddl-auto=validate`) sin errores.
  - **Criterio de finalización (Done when):** `./mvnw test -Dtest=ApplicationIntegrationTest` ejecuta contra Docker PostgreSQL, pasa al 100% y confirma la ejecución de la migración V1.

- [x] **T5: Suite de pruebas de integridad relacional del esquema (`SchemaIntegrityTest`)**
  - **Requisitos cubiertos:** FND-REQ-03, FND-REQ-04, EC-03, EC-04, EC-07.
  - **Archivos a crear:** `src/test/java/com/eyelesscode/task_by_task/shared/SchemaIntegrityTest.java`.
  - **Descripción:** Implementar pruebas con `JdbcTemplate` sobre el contenedor PostgreSQL para validar que las restricciones relacionales se disparan:
    - Violación del check de tarea con `due_time` sin `due_date`.
    - Violación del índice único parcial al intentar insertar un segundo grupo con `is_personal = true` para el mismo usuario.
    - Violación del índice único de etiqueta al intentar insertar dos nombres idénticos tras `TRIM(LOWER(name))` en el mismo grupo.
    - Violación de la unicidad compuesta `(group_id, user_id)` en `group_memberships`.
  - **Criterio de finalización (Done when):** `./mvnw test -Dtest=SchemaIntegrityTest` pasa al 100%, confirmando el comportamiento de las restricciones del DDL en la base de datos real.

- [x] **T6: Infraestructura de manejo global de errores RFC 7807 (`shared.error`)**
  - **Requisitos cubiertos:** FND-REQ-14 a FND-REQ-21, RNF-07, RNF-14, RNF-18, RNF-19.
  - **Archivos a crear:**
    - `src/main/java/com/eyelesscode/task_by_task/shared/error/ResourceNotFoundException.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/error/ResourceConflictException.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/error/BadRequestException.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/error/ValidationErrorDetail.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/error/GlobalExceptionHandler.java`
    - `src/test/java/com/eyelesscode/task_by_task/shared/error/GlobalExceptionHandlerTest.java`
  - **Descripción:**
    - Crear excepciones de dominio compartidas y record para detalle de validación.
    - Crear `GlobalExceptionHandler` con `@RestControllerAdvice` retornando `ProblemDetail` estandarizado con mensajes en español para 400 (validación y formato), 404 (no encontrado), 409 (conflicto de estado) y 500 (inesperado seguro).
    - Crear suite de pruebas unitarias/MockMvc validando la estructura del JSON devuelto en cada caso.
  - **Criterio de finalización (Done when):** `./mvnw test -Dtest=GlobalExceptionHandlerTest` pasa al 100% y confirma respuestas RFC 7807 en español.

- [x] **T7: Proveedor de tiempo, zonas horarias y funciones puras (`shared.time`)**
  - **Requisitos cubiertos:** FND-REQ-26 a FND-REQ-31, RF-12, RF-14, RF-28, RNF-14, EC-01.
  - **Archivos a crear:**
    - `src/main/java/com/eyelesscode/task_by_task/shared/time/DateTimeProvider.java`
    - `src/test/java/com/eyelesscode/task_by_task/shared/time/DateTimeProviderTest.java`
  - **Descripción:**
    - Implementar `DateTimeProvider` inyectando `Clock`, con métodos puros para `now()`, `today(ZoneId)`, cálculo de estado vencido (23:59:59.999 local para tareas sin hora vs hora exacta), y cálculo de instante de disparo de avisos (09:00 AM local, o inmediato si es para "hoy" y ya pasaron las 09:00 AM).
    - Implementar pruebas unitarias exhaustivas fijando el `Clock` para probar distintos escenarios de horas, zonas horarias (ej. `America/Bogota`, `UTC`) y casos borde.
  - **Criterio de finalización (Done when):** `./mvnw test -Dtest=DateTimeProviderTest` pasa al 100% con cobertura de todos los casos de la regla de las 09:00 AM y el vencimiento a fin de día.

- [x] **T8: Infraestructura de paginación por cursor Keyset (`shared.pagination`)**
  - **Requisitos cubiertos:** FND-REQ-22 a FND-REQ-25, RNF-04, RF-15.
  - **Archivos a crear:**
    - `src/main/java/com/eyelesscode/task_by_task/shared/pagination/KeysetCursor.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/pagination/KeysetPage.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/pagination/KeysetCursorCodec.java`
    - `src/test/java/com/eyelesscode/task_by_task/shared/pagination/KeysetCursorCodecTest.java`
  - **Descripción:**
    - Crear `KeysetCursor` (record para encapsular tuplas de ordenamiento) y `KeysetPage` (record genérico de respuesta con `content`, `nextCursor`, `hasNext`, `size`).
    - Implementar `KeysetCursorCodec` con funciones puras para serializar y deserializar tokens opacos Base64 URL-safe, arrojando `BadRequestException` en español ante tokens manipulados o corruptos.
    - Crear suite de pruebas unitarias verificando ida y vuelta (round-trip) y manejo de errores.
  - **Criterio de finalización (Done when):** `./mvnw test -Dtest=KeysetCursorCodecTest` pasa al 100%.

- [x] **T9: Control de tasa defensivo con Bucket4j y Caffeine (`shared.ratelimit`)**
  - **Requisitos cubiertos:** FND-REQ-32, FND-REQ-33, RNF-06.
  - **Archivos a crear:**
    - `src/main/java/com/eyelesscode/task_by_task/shared/ratelimit/RateLimitException.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/ratelimit/RateLimitService.java`
    - `src/test/java/com/eyelesscode/task_by_task/shared/ratelimit/RateLimitServiceTest.java`
  - **Descripción:**
    - Crear `RateLimitException` con duración de reintento (`retryAfterSeconds`).
    - Crear `RateLimitService` usando Token Bucket de Bucket4j respaldado por Caffeine con límite de 10,000 entradas y TTL de 10 minutos. Capacidad por defecto de 5 tokens por minuto para rutas sensibles.
    - Integrar el manejo de `RateLimitException` en `GlobalExceptionHandler` retornando HTTP 429 con cabecera `Retry-After`.
    - Crear suite de pruebas unitarias comprobando el consumo de tokens y el bloqueo en el sexto intento consecutivo.
  - **Criterio de finalización (Done when):** `./mvnw test -Dtest=RateLimitServiceTest` pasa al 100%.

- [x] **T10: Configuración base de Spring Security, CSRF y sesiones persistentes JDBC (`shared.security`)**
  - **Requisitos cubiertos:** FND-REQ-09 a FND-REQ-13, FND-REQ-16, FND-REQ-19, RF-02, RNF-05, RNF-08, RNF-09.
  - **Archivos a crear:**
    - `src/main/java/com/eyelesscode/task_by_task/shared/security/PasswordEncoderConfig.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/security/RestAuthenticationEntryPoint.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/security/RestAccessDeniedHandler.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/security/SecurityConfig.java`
    - `src/test/java/com/eyelesscode/task_by_task/shared/security/SecurityIntegrationTest.java`
  - **Descripción:**
    - Configurar `DelegatingPasswordEncoder` con `{bcrypt}` como default.
    - Crear `RestAuthenticationEntryPoint` (401 RFC 7807) y `RestAccessDeniedHandler` (403 RFC 7807) con respuestas en español.
    - Configurar `SecurityConfig` con `SecurityFilterChain`: CSRF habilitado vía cookie, sesión JDBC de 7 días, `HttpOnly` y rutas públicas permitidas (`/actuator/health`, Swagger UI, endpoints de autenticación).
    - Crear `SecurityIntegrationTest` extendiendo de `AbstractIntegrationTest` para verificar el rechazo con 401 RFC 7807 a rutas protegidas y la exigencia de CSRF en métodos mutables.
  - **Criterio de finalización (Done when):** `./mvnw test -Dtest=SecurityIntegrationTest` pasa al 100%.

- [x] **T11: Verificación global de calidad de código, formato y ejecución de pruebas**
  - **Requisitos cubiertos:** Constitución Principio 6, RNF-07, RNF-14.
  - **Archivos a verificar:** Todo el repositorio.
  - **Descripción:**
    - Ejecutar `./mvnw spotless:apply` seguido de `./mvnw spotless:check compile test-compile`.
    - Ejecutar la suite completa de pruebas con `./mvnw test`.
    - Verificar que no existan advertencias de compilación ni secretos expuestos en Git.
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test` pasan al 100% con 0 errores y 0 fallos.
