# Plan Técnico de Implementación: 000-foundation (Base Arquitectural y de Persistencia)

**Fase:** Foundation  
**Estado:** approved (basado en `spec.md` v1.1 aprobada)  
**Fecha:** 2026-11-09  
**Autor:** SDD Planner Agent  
**Referencias:**
- `docs/specs/000-foundation/spec.md` (FND-REQ-01 a FND-REQ-37)
- `docs/constitution.md` (Principios 1 a 6)
- `AGENTS.md` (Convenciones técnicas y domain rules)
- `MEMORY.md` (Decisiones arquitecturales y mitigación de escollos)
- `.agents/skills/supabase-postgres-best-practices/SKILL.md` (Reglas de diseño de PostgreSQL/Supabase)

---

## 1. Resumen Ejecutivo y Enfoque de Diseño

El objetivo de `000-foundation` es dotar al proyecto Task by Task de su columna vertebral de persistencia, seguridad, manejo de errores, paginación, normalización temporal y control de acceso defensivo, sin escribir lógica de negocio de los dominios funcionales (cuentas, grupos o tareas).

El enfoque técnico se rige por:
1. **Extensibilidad relacional sin migraciones destructivas (`RNF-20`):** Migración Flyway `V1__init_schema.sql` cubriendo todo el modelo del SRS (Apéndice B) e infraestructura (`SPRING_SESSION`, `scheduled_tasks` de `db-scheduler`), diseñada siguiendo las mejores prácticas de Supabase/PostgreSQL.
2. **Defensa en profundidad a costo $0:** Sesiones JDBC de 7 días (`RF-02`), cookies `HttpOnly` + CSRF, contraseñas `{bcrypt}` listas para migración transparente (`RNF-05`), y rate limiting en memoria con `Bucket4j` + `Caffeine` (`RNF-06`).
3. **Respuestas de error uniformes en español (RFC 7807 `ProblemDetail`):** Manejador global de excepciones con contrato estandarizado (`RNF-07`, `RNF-14`).
4. **Paginación determinista Keyset (Seek):** Protocolo de cursores opacos $O(1)$ (`RNF-04`) con ordenamiento compuesto estricto.
5. **Funciones puras para fechas y zonas horarias:** Abstracción inyectable de reloj (`Clock`) para pruebas deterministas de la regla de las 09:00 AM y el vencimiento a las 23:59:59.999 local.
6. **Verificación automatizada con paridad real:** Pruebas de integración con `@SpringBootTest` + `Testcontainers` (`postgres:alpine`) y `spring.jpa.hibernate.ddl-auto=validate`.

---

## 2. Inventario de Archivos Afectados

```
JavaSpringBoot-TaskByTask/
├── pom.xml                                                            # [MODIFICAR] Starters, dependencias y plugin Spotless
├── .gitignore                                                         # [MODIFICAR] Agregar .env y application-local.yml
├── src/main/resources/
│   ├── application.yml                                                # [CREAR] Configuración principal (perfiles, DataSource, JPA validate)
│   ├── application-dev.yml                                            # [CREAR] Perfil dev para Supabase Session Mode (puerto 5432)
│   └── db/migration/
│       └── V1__init_schema.sql                                        # [CREAR] DDL inicial completo (tablas de dominio + infra)
├── src/main/java/com/eyelesscode/task_by_task/shared/
│   ├── error/
│   │   ├── GlobalExceptionHandler.java                               # [CREAR] @RestControllerAdvice RFC 7807 en español
│   │   ├── ResourceNotFoundException.java                            # [CREAR] 404 Not Found controlado
│   │   ├── ResourceConflictException.java                            # [CREAR] 409 Conflict controlado (inmutabilidad Personal)
│   │   ├── BadRequestException.java                                  # [CREAR] 400 Bad Request genérico / formato
│   │   └── ValidationErrorDetail.java                                # [CREAR] Record para errores de campo
│   ├── security/
│   │   ├── SecurityConfig.java                                       # [CREAR] SecurityFilterChain, CSRF, SessionManagement
│   │   ├── PasswordEncoderConfig.java                                # [CREAR] DelegatingPasswordEncoder ({bcrypt})
│   │   ├── RestAuthenticationEntryPoint.java                         # [CREAR] 401 RFC 7807 en español
│   │   └── RestAccessDeniedHandler.java                              # [CREAR] 403 RFC 7807 en español
│   ├── ratelimit/
│   │   ├── RateLimitService.java                                     # [CREAR] Bucket4j + Caffeine token bucket por IP
│   │   └── RateLimitException.java                                   # [CREAR] Excepción 429 con retry-after duration
│   ├── pagination/
│   │   ├── KeysetCursor.java                                         # [CREAR] Record para deserialización de cursores
│   │   ├── KeysetPage.java                                           # [CREAR] Record genérico de respuesta paginada
│   │   └── KeysetCursorCodec.java                                    # [CREAR] Funciones puras de codificación Base64 URL-safe
│   └── time/
│       └── DateTimeProvider.java                                     # [CREAR] Proveedor inyectable de Clock, ZoneId y cálculos temporales
└── src/test/
    ├── resources/
    │   └── application-test.yml                                       # [CREAR] Perfil de prueba para Testcontainers
    └── java/com/eyelesscode/task_by_task/shared/
        ├── AbstractIntegrationTest.java                              # [CREAR] Clase base con Testcontainers postgres:alpine
        ├── ApplicationIntegrationTest.java                           # [CREAR] Smoke test de arranque, Flyway y ddl-auto=validate
        ├── SchemaIntegrityTest.java                                  # [CREAR] Test de restricciones de base de datos V1
        ├── error/
        │   └── GlobalExceptionHandlerTest.java                       # [CREAR] Test unitario/MockMvc de respuestas RFC 7807
        ├── security/
        │   └── SecurityIntegrationTest.java                          # [CREAR] Test de sesión HttpOnly, CSRF y 401
        ├── ratelimit/
        │   └── RateLimitServiceTest.java                             # [CREAR] Test de consumo y saturación de tokens
        ├── pagination/
        │   └── KeysetCursorCodecTest.java                            # [CREAR] Test de codificación, decodificación y manipulación
        └── time/
            └── DateTimeProviderTest.java                             # [CREAR] Test de reglas temporales (09:00 AM y 23:59:59)
```

---

## 3. Configuración de Dependencias y Herramientas (`pom.xml`)

### 3.1 Starters y Librerías Requeridas
- **Spring Boot Starters:**
  - `spring-boot-starter-web`: Servidor Tomcat embebido y controladores REST.
  - `spring-boot-starter-data-jpa`: Spring Data JPA y Hibernate 6.
  - `spring-boot-starter-security`: Spring Security 6 (autenticación y filtros).
  - `spring-boot-starter-validation`: Jakarta Validation (validaciones de payload).
  - `spring-boot-starter-actuator`: Health check y métricas operativas.
- **Persistencia y Migraciones:**
  - `org.postgresql:postgresql`: Driver JDBC para PostgreSQL.
  - `org.flywaydb:flyway-core` + `org.flywaydb:flyway-database-postgresql`: Motor de migraciones versionadas.
  - `org.springframework.session:spring-session-jdbc`: Persistencia de sesiones HTTP en PostgreSQL.
- **Programación de Trabajos en Segundo Plano:**
  - `com.github.kagkarlsson:db-scheduler-spring-boot-starter:15.0.1`: Planificador de tareas persistente sobre tabla PostgreSQL única.
- **Defensa y Seguridad:**
  - `com.bucket4j:bucket4j_jdk17-core:8.10.1`: Algoritmo Token Bucket en memoria.
  - `com.github.ben-manes.caffeine:caffeine:3.1.8`: Caché acotada de alto rendimiento para el almacenamiento de buckets por IP.
  - `org.bouncycastle:bcprov-jdk18on:1.78.1`: Proveedor criptográfico para soporte futuro de firmas VAPID Web Push (RFC 8292).
- **Documentación API:**
  - `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0`: Documentación OpenAPI 3 / Swagger UI.
- **Pruebas y Verificación:**
  - `spring-boot-starter-test`: JUnit 5, AssertJ, Mockito.
  - `org.testcontainers:postgresql:1.20.1` + `org.testcontainers:junit-jupiter:1.20.1`: Contenedor Docker de PostgreSQL para paridad de pruebas.
  - `org.awaitility:awaitility:4.2.2`: Verificación de aserciones asíncronas y temporales.

### 3.2 Formateo Automático de Código (`spotless-maven-plugin`)
- Plugin `com.diffplug.spotless:spotless-maven-plugin:2.43.0`:
  - `java`: Formateo con `palantir-java-format` o `google-java-format(1.22.0)` estilo 4 espacios, eliminación de imports no usados (`removeUnusedImports()`), e identación limpia.
  - Integrado a las fases del ciclo Maven y verificable con `./mvnw spotless:check`.

---

## 4. Diseño del Esquema de Persistencia (`V1__init_schema.sql`)

Diseñado siguiendo las directrices de `.agents/skills/supabase-postgres-best-practices/SKILL.md`:
- Claves primarias estándar `BIGINT GENERATED ALWAYS AS IDENTITY`.
- Timestamps canónicos en `TIMESTAMPTZ` (UTC).
- Índices automáticos sobre todas las claves foráneas para evitar escaneos secuenciales en operaciones JOIN y CASCADE (`query-missing-indexes`).
- Índices parciales para estados filtrados y unicidad condicional (`query-partial-indexes`).
- Índices funcionales/expresivos para normalización insensible a mayúsculas y espacios.

```sql
-- ============================================================================
-- V1__init_schema.sql — Task by Task Database Baseline Schema
-- Target: PostgreSQL 15+ (Supabase "public" schema)
-- Constraints: Strict validation, RNF-20 extensibility, zero destructive migrations
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. USERS & CREDENTIALS
-- ----------------------------------------------------------------------------
CREATE TABLE users (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    username VARCHAR(30) NOT NULL,
    display_name VARCHAR(50) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    timezone VARCHAR(50) NOT NULL DEFAULT 'UTC',
    time_format VARCHAR(3) NOT NULL DEFAULT '24H',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_users_username_format CHECK (username ~ '^[A-Za-z0-9]{3,30}$'),
    CONSTRAINT chk_users_display_name_len CHECK (char_length(display_name) >= 2 AND char_length(display_name) <= 50),
    CONSTRAINT chk_users_time_format CHECK (time_format IN ('24H', '12H'))
);

-- Case-insensitive unique indexes for login identifiers
CREATE UNIQUE INDEX uq_users_email_lower ON users (LOWER(email));
CREATE UNIQUE INDEX uq_users_username_lower ON users (LOWER(username));

-- ----------------------------------------------------------------------------
-- 2. RECOVERY CODES (8 single-use codes per user, RF-01, RF-03)
-- ----------------------------------------------------------------------------
CREATE TABLE recovery_codes (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash VARCHAR(255) NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    used_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_recovery_codes_user_id ON recovery_codes (user_id);

-- ----------------------------------------------------------------------------
-- 3. GROUPS & COLLABORATIVE MEMBERSHIPS (RNF-20 Extensibility)
-- ----------------------------------------------------------------------------
CREATE TABLE groups (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    creator_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(30) NOT NULL,
    is_personal BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_groups_name_len CHECK (char_length(name) >= 3 AND char_length(name) <= 30)
);

CREATE INDEX idx_groups_creator_id ON groups (creator_id);

-- Enforce exactly ONE "Personal" group per user (FND-REQ-04, RF-06)
CREATE UNIQUE INDEX uq_groups_creator_personal ON groups (creator_id) WHERE is_personal = TRUE;

-- Group Memberships supporting future shared roles (CREATOR, MANAGER, VISITOR)
CREATE TABLE group_memberships (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_group_memberships_role CHECK (role IN ('CREATOR', 'MANAGER', 'VISITOR')),
    CONSTRAINT uq_group_memberships_group_user UNIQUE (group_id, user_id)
);

CREATE INDEX idx_group_memberships_user_id ON group_memberships (user_id);
CREATE INDEX idx_group_memberships_group_id ON group_memberships (group_id);

-- ----------------------------------------------------------------------------
-- 4. TAGS (Group-Scoped, Case/Whitespace Insensitive, RF-15, RF-22)
-- ----------------------------------------------------------------------------
CREATE TABLE tags (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    name VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_tags_name_len CHECK (char_length(TRIM(name)) >= 1 AND char_length(TRIM(name)) <= 30),
    CONSTRAINT chk_tags_no_delimiters CHECK (name !~ '[,;]')
);

CREATE INDEX idx_tags_group_id ON tags (group_id);

-- Unique tag name per group ignoring case and whitespace (EC-03, FND-REQ-03)
CREATE UNIQUE INDEX uq_tags_group_normalized_name ON tags (group_id, TRIM(LOWER(name)));

-- ----------------------------------------------------------------------------
-- 5. TASKS (Core Entity, RNF-20 nullable assignee, composite keyset index)
-- ----------------------------------------------------------------------------
CREATE TABLE tasks (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    assigned_user_id BIGINT NULL REFERENCES users(id) ON DELETE SET NULL,
    title VARCHAR(100) NOT NULL,
    description VARCHAR(750) NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    completed_at TIMESTAMPTZ NULL,
    due_date DATE NULL,
    due_time TIME NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_tasks_title_len CHECK (char_length(title) >= 2 AND char_length(title) <= 100),
    CONSTRAINT chk_tasks_due_time_requires_date CHECK (due_time IS NULL OR due_date IS NOT NULL)
);

CREATE INDEX idx_tasks_group_id ON tasks (group_id);
CREATE INDEX idx_tasks_assigned_user_id ON tasks (assigned_user_id) WHERE assigned_user_id IS NOT NULL;

-- Keyset pagination covering index matching default composite order (FND-REQ-24, RF-15)
CREATE INDEX idx_tasks_keyset_order ON tasks (
    group_id,
    completed ASC,
    due_date ASC NULLS LAST,
    due_time ASC NULLS LAST,
    created_at ASC,
    id ASC
);

-- ----------------------------------------------------------------------------
-- 6. TASK_TAGS (Many-to-Many Association)
-- ----------------------------------------------------------------------------
CREATE TABLE task_tags (
    task_id BIGINT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    tag_id BIGINT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id, tag_id)
);

CREATE INDEX idx_task_tags_tag_id ON task_tags (tag_id);

-- ----------------------------------------------------------------------------
-- 7. WEB PUSH SUBSCRIPTIONS (Browser Notifications, RF-27)
-- ----------------------------------------------------------------------------
CREATE TABLE push_subscriptions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    endpoint TEXT NOT NULL UNIQUE,
    p256dh VARCHAR(255) NOT NULL,
    auth VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_push_subscriptions_user_id ON push_subscriptions (user_id);

-- ----------------------------------------------------------------------------
-- 8. NOTIFICATIONS (In-app Notification Center, RF-29, 30-day retention)
-- ----------------------------------------------------------------------------
CREATE TABLE notifications (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_type VARCHAR(50) NOT NULL,
    content VARCHAR(500) NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notifications_user_id_created ON notifications (user_id, created_at DESC, id DESC);

-- ----------------------------------------------------------------------------
-- 9. SPRING SESSION JDBC INFRASTRUCTURE TABLES (RF-02, RNF-09)
-- ----------------------------------------------------------------------------
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL PRIMARY KEY,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BYTEA NOT NULL,
    PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME)
);

-- ----------------------------------------------------------------------------
-- 10. DB-SCHEDULER INFRASTRUCTURE TABLE (Kagkarlsson, RF-13, RF-28)
-- ----------------------------------------------------------------------------
CREATE TABLE scheduled_tasks (
    task_name VARCHAR(100) NOT NULL,
    task_instance VARCHAR(100) NOT NULL,
    task_data BYTEA,
    execution_time TIMESTAMPTZ NOT NULL,
    picked BOOLEAN NOT NULL DEFAULT FALSE,
    picked_by VARCHAR(50),
    last_success TIMESTAMPTZ,
    last_failure TIMESTAMPTZ,
    consecutive_failures INT,
    last_heartbeat TIMESTAMPTZ,
    version BIGINT NOT NULL,
    PRIMARY KEY (task_name, task_instance)
);

CREATE INDEX idx_scheduled_tasks_execution ON scheduled_tasks (execution_time);
```

---

## 5. Diseño de Componentes Transversales (`shared`)

### 5.1 Manejo Global de Errores RFC 7807 (`shared.error`)
- **`GlobalExceptionHandler` (`@RestControllerAdvice`):**
  - Mapea excepciones a respuestas RFC 7807 `ProblemDetail`:
    - `MethodArgumentNotValidException` / `ConstraintViolationException` -> `HTTP 400 Bad Request` con título `"Error de validación"` y propiedad adicional `invalidParams: [{name, reason}]`.
    - `BadRequestException` -> `HTTP 400 Bad Request` con título `"Solicitud inválida"`.
    - `ResourceNotFoundException` -> `HTTP 404 Not Found` con título `"Recurso no encontrado"`.
    - `ResourceConflictException` -> `HTTP 409 Conflict` con título `"Conflicto de estado"`.
    - `AccessDeniedException` -> `HTTP 403 Forbidden` con título `"Acceso denegado"` y detalle `"No tiene permisos para realizar esta acción"`.
    - `RateLimitException` -> `HTTP 429 Too Many Requests` con título `"Demasiadas solicitudes"` y encabezado `Retry-After`.
    - `Exception` genérica -> `HTTP 500 Internal Server Error` con título `"Error interno del servidor"`, detalle `"Ocurrió un error inesperado. Intente nuevamente más tarde."` (sin exponer trazas internas del servidor al cliente).

### 5.2 Seguridad Base y Sesiones JDBC (`shared.security`)
- **`SecurityConfig` (`@Configuration`):**
  - `SecurityFilterChain`:
    - Deshabilita `httpBasic` y `formLogin` por defecto (usaremos endpoints REST explícitos en `001-auth-and-users`).
    - Habilita CSRF con `CookieCsrfTokenRepository.withHttpOnlyFalse()` para lectura de cookie de token por clientes web, requiriendo token en `POST`, `PUT`, `PATCH`, `DELETE`.
    - Manejo de excepciones de seguridad con entrypoints RFC 7807:
      - `RestAuthenticationEntryPoint`: `HTTP 401 Unauthorized` (`ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Debe iniciar sesión para acceder a este recurso")`).
      - `RestAccessDeniedHandler`: `HTTP 403 Forbidden` (`ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "No tiene permisos suficientes para acceder a este recurso")`).
    - Configura sesiones stateful delegadas a Spring Session JDBC con tiempo de vida máximo de 7 días (`604800` segundos) y cookie `SameSite=Lax`, `HttpOnly=true`, `Secure=true` (en perfiles no locales).
    - Rutas públicas permitidas: `/actuator/health`, `/v3/api-docs/**`, `/swagger-ui/**`, `/api/auth/register`, `/api/auth/login`, `/api/auth/recover-password`.
- **`PasswordEncoderConfig`:**
  - Expone `PasswordEncoder` mediante `PasswordEncoderFactories.createDelegatingPasswordEncoder()`, usando `{bcrypt}` como algoritmo predeterminado para contraseñas y los 8 códigos de recuperación (`RF-01`, `RF-03`).

### 5.3 Control de Tasa Defensivo (`shared.ratelimit`)
- **`RateLimitService`:**
  - Implementa un Token Bucket en memoria usando Bucket4j respaldado por una caché Caffeine:
    - Capacidad de bucket: 5 tokens por ventana de 1 minuto (recarga continua o por bloque).
    - Caché Caffeine: `Caffeine.newBuilder().maximumSize(10_000).expireAfterAccess(Duration.ofMinutes(10)).build()`.
    - Clave de partición: Dirección IP del cliente (`X-Forwarded-For` o `HttpServletRequest.getRemoteAddr()`).
    - Método: `boolean tryConsume(String key)`. Si falla, arroja `RateLimitException(retryAfterSeconds)`.

### 5.4 Paginación por Cursor Keyset (`shared.pagination`)
- **`KeysetCursorCodec`:**
  - Codifica una tupla de ordenamiento compuesta en un token opaco Base64 URL-safe:
    - Para tareas: `(completed, dueDate, dueTime, createdAt, id)`.
  - Decodifica el token validando su formato; si el token está corrompido o manipulado, arroja `BadRequestException("El cursor de paginación proporcionado no es válido")`.
- **`KeysetPage<T>` (Record genérico):**
  - Componentes: `List<T> content`, `String nextCursor`, `boolean hasNext`, `int size`.

### 5.5 Proveedor de Tiempo y Funciones Puras (`shared.time`)
- **`DateTimeProvider`:**
  - Componente inyectable con `Clock` como dependencia (permite congelar o adelantar el tiempo en pruebas unitarias):
    - `Instant now()`: Momento actual en UTC.
    - `LocalDate today(ZoneId zoneId)`: Fecha actual en la zona local del usuario.
    - `boolean isOverdue(LocalDate dueDate, LocalTime dueTime, ZoneId zoneId, Instant currentInstant)`:
      - Si `dueTime != null`: Vencida si `currentInstant > ZonedDateTime(dueDate, dueTime, zoneId).toInstant()`.
      - Si `dueTime == null`: Vencida si `currentInstant > ZonedDateTime(dueDate, 23:59:59.999, zoneId).toInstant()`.
    - `Instant calculateDueTriggerInstant(LocalDate dueDate, LocalTime dueTime, ZoneId zoneId, Instant currentInstant)`:
      - Si `dueTime != null`: Momento exacto `ZonedDateTime(dueDate, dueTime, zoneId).toInstant()`.
      - Si `dueTime == null`:
        - Instante 09:00 AM local = `ZonedDateTime(dueDate, 09:00:00, zoneId).toInstant()`.
        - Si `dueDate.equals(today(zoneId))` y `currentInstant >= 09:00 AM instant` y `!isOverdue(...)`: retorna `currentInstant` (disparo inmediato según `RF-28` y `FND-REQ-30`).
        - En cualquier otro caso: retorna instante 09:00 AM local.

---

## 6. Decisiones de Diseño y Alternativas Descartadas

| Decisión de Diseño | Opción Elegida | Alternativa Descartada | Justificación Técnica |
| :--- | :--- | :--- | :--- |
| **Identificadores primarios** | `BIGINT GENERATED ALWAYS AS IDENTITY` | `UUIDv4` / `UUIDv7` | Maximiza el rendimiento de índices B-tree, minimiza el tamaño de almacenamiento en PostgreSQL (8 bytes vs 16 bytes), y provee desempate natural determinista para paginación Keyset (`id ASC`). |
| **Almacenamiento de sesiones** | `Spring Session JDBC` en PostgreSQL | `Spring Session Redis` / `JWT` sin estado | Cero costo adicional de infraestructura ($0 budget, `RNF-02`). Permite revocación instantánea en logout (`RF-02`) y borrado de cuenta (`RF-05`), lo cual JWT no permite sin listas negras complejas. |
| **Defensa contra fuerza bruta** | `Bucket4j` + `Caffeine` en memoria | Rate limiting en base de datos PostgreSQL | Cero IOPS en la base de datos de Supabase. Resuelve picos de peticiones maliciosas en memoria sin bloqueos de filas en base de datos. Límites acotados (10,000 entradas) previenen fugas de memoria. |
| **Programación de notificaciones** | `db-scheduler` (Kagkarlsson) | `Quartz Scheduler` / Polling con `@Scheduled` | `db-scheduler` utiliza una única tabla ligera, soporta reprogramación y cancelación atómica por ID (`task-due-{taskId}`), y sobrevive a reinicios sin el peso de 11 tablas de Quartz ni el desperdicio de CPU del polling cada minuto. |
| **Paginación progresiva** | Keyset / Seek pagination con cursores opacos | Paginación tradicional `Pageable` (`OFFSET / LIMIT`) | Complejidad $O(1)$ sin importar la profundidad de la lista (`RNF-04`). Evita el "deslizamiento" de páginas (saltos o duplicados) cuando se completan o insertan tareas durante la navegación. |
| **Validación de esquema JPA** | `spring.jpa.hibernate.ddl-auto=validate` | `update` o `create-drop` | Garantiza que Flyway sea la única fuente de verdad inmutable de la base de datos en producción y pruebas, detectando desalineaciones antes del despliegue. |

---

## 7. Estrategia de Pruebas Automatizadas

1. **Entorno de Prueba (`Testcontainers`):**
   - Clase base abstracta `AbstractIntegrationTest`:
     - Inicia un contenedor singleton `PostgreSQLContainer<?>` con imagen `postgres:alpine`.
     - Configura dinámicamente las propiedades JDBC (`@ServiceConnection` o `@DynamicPropertySource`).
     - Flyway aplica automáticamente `V1__init_schema.sql` sobre el contenedor.
     - Hibernate valida el esquema (`ddl-auto=validate`).
2. **Suites de Prueba en `000-foundation`:**
   - **`ApplicationIntegrationTest`:** Smoke test que levanta el contexto completo de Spring Boot, verifica que Flyway ejecute la migración y que `ddl-auto=validate` termine sin advertencias ni fallos.
   - **`SchemaIntegrityTest`:** Prueba directa con `JdbcTemplate` para verificar:
     - Check constraint: insertar tarea con `due_time` sin `due_date` arroja error de violación de restricción (`chk_tasks_due_time_requires_date`).
     - Unicidad del grupo Personal: intentar insertar un segundo grupo con `is_personal = true` para el mismo usuario viola el índice parcial `uq_groups_creator_personal`.
     - Unicidad de etiquetas: insertar dos etiquetas con el mismo nombre normalizado (`" Urgente "` y `"urgente"`) en el mismo grupo viola `uq_tags_group_normalized_name`.
   - **`GlobalExceptionHandlerTest`:** Pruebas unitarias/MockMvc de serialización RFC 7807 en español para códigos 400, 401, 403, 404, 409, 429 y 500.
   - **`SecurityIntegrationTest`:** Verifica que peticiones sin sesión retornen 401 RFC 7807, que peticiones POST sin CSRF fallen con 403, y que las cookies de sesión tengan `HttpOnly`.
   - **`RateLimitServiceTest`:** Verifica que tras 5 intentos fallidos en 1 minuto para una misma IP, el sexto intento sea bloqueado y retorne 429.
   - **`KeysetCursorCodecTest`:** Verifica la codificación/decodificación determinista de cursores y el rechazo con 400 ante tokens truncados o manipulados.
   - **`DateTimeProviderTest`:** Verifica cálculos de vencimiento (23:59:59.999 local vs exacto) y la regla de disparo de avisos a las 09:00 AM y para "hoy" después de las 09:00 AM.

---

## 8. Matriz de Cobertura de Requisitos (FND-REQ -> Componentes Técnicos)

| Requisito EARS | Componente Técnico Implementador | Archivos Afectados |
| :--- | :--- | :--- |
| **FND-REQ-01** a **FND-REQ-08** | DDL de Flyway (`V1__init_schema.sql`) | `src/main/resources/db/migration/V1__init_schema.sql` |
| **FND-REQ-09** a **FND-REQ-13** | Configuración de Seguridad y Sesión JDBC | `SecurityConfig.java`, `PasswordEncoderConfig.java`, `SPRING_SESSION` |
| **FND-REQ-14** a **FND-REQ-21** | Manejador Global RFC 7807 y EntryPoints | `GlobalExceptionHandler.java`, `RestAuthenticationEntryPoint.java`, `RestAccessDeniedHandler.java` |
| **FND-REQ-22** a **FND-REQ-25** | Paginación Keyset y Codificador de Cursores | `KeysetCursor.java`, `KeysetPage.java`, `KeysetCursorCodec.java`, `idx_tasks_keyset_order` |
| **FND-REQ-26** a **FND-REQ-31** | Proveedor de Fechas, Zonas y db-scheduler | `DateTimeProvider.java`, `scheduled_tasks` |
| **FND-REQ-32**, **FND-REQ-33** | Rate Limiting con Bucket4j y Caffeine | `RateLimitService.java`, `RateLimitException.java` |
| **FND-REQ-34**, **FND-REQ-35** | Configuración y Variables de Entorno | `application.yml`, `application-dev.yml`, `.gitignore` |
| **FND-REQ-36**, **FND-REQ-37** | Paridad de Pruebas en Testcontainers | `AbstractIntegrationTest.java`, `ApplicationIntegrationTest.java` |
