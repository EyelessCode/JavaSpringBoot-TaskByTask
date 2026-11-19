# Plan Técnico de Implementación: 001-auth-and-users (Autenticación y Gestión de Usuarios)

**Fase:** 001-auth-and-users  
**Estado:** approved (basado en `spec.md` v1.1 aprobada)  
**Fecha:** 2026-11-10  
**Autor:** SDD Planner Agent  
**Referencias:**
- `docs/specs/001-auth-and-users/spec.md` (AUTH-REQ-01 a AUTH-REQ-30, EC-01 a EC-12)
- `docs/specs/000-foundation/spec.md` y `plan.md` (Esquema relacional V1, RateLimitService, GlobalExceptionHandler, SecurityConfig)
- `docs/constitution.md` (Principios 1 a 6)
- `AGENTS.md` (Convenciones técnicas, domain rules y verification)
- `MEMORY.md` (Decisiones y estado actual)
- `src/main/resources/db/migration/V1__init_schema.sql` (Esquema DDL de referencia para `ddl-auto=validate`)

---

## 1. Resumen Ejecutivo y Enfoque de Diseño

El objetivo del módulo **001-auth-and-users** es dotar a **Task by Task** de la funcionalidad vertical completa de registro, autenticación, gestión de sesiones persistentes (Spring Session JDBC), autorrecuperación de contraseñas mediante códigos de un solo uso, administración de perfil y preferencias, y eliminación definitiva de cuentas en cascada, garantizando paridad estricta con las reglas del SRS y el esquema `V1__init_schema.sql` en modo `ddl-auto=validate`.

El enfoque técnico se rige por:
1. **Arquitectura Package-by-Feature y Pure Java 21:** Implementación en paquetes verticales (`user/`, `auth/`, `group/`) con DTOs inmutables basados en `record`, mappers manuales explícitos (cero Lombok, cero MapStruct), y clases de servicio con inyección por constructor.
2. **Defensa en profundidad y mitigación de canales laterales:** Rate limiting defensivo con `RateLimitService` (Bucket4j + Caffeine) en endpoints públicos, hashing irreversible `{bcrypt}` para contraseñas y códigos de recuperación, y verificación simulada con costo BCrypt idéntico (*dummy hash verification*) ante correos no registrados para neutralizar ataques de timing.
3. **Identidad inmutable en sesiones:** Almacenamiento de `String.valueOf(user.id)` como `PRINCIPAL_NAME` en Spring Session JDBC, permitiendo que la modificación de `email` o `username` nunca desincronice el índice de sesiones ni impida la revocación masiva.
4. **Política granular de invalidación de sesiones:**
   - En recuperación de contraseña (`POST /api/v1/auth/recover-password`): invalidación de absolutamente todas las sesiones del usuario.
   - En cambio voluntario de contraseña (`POST /api/v1/users/me/password`): invalidación de todas las *demás* sesiones activas en la base de datos, preservando la sesión activa del cliente actual.
5. **Transaccionalidad y extensibilidad atómica (`RNF-20`):** Creación atómica de la cuenta con su grupo `"Personal"` (`is_personal = true`) y membresía `CREATOR`, e invariante estricto de máximo 8 códigos de recuperación mediante eliminación física (`DELETE`) previa a la reinserción en regeneración.
6. **Verificación automatizada con paridad real:** Suites de prueba completas con `@SpringBootTest` y `Testcontainers` (`postgres:alpine`), asegurando que las restricciones relacionales de PostgreSQL se cumplan sin discrepancias.

---

## 2. Inventario de Archivos Afectados

```
JavaSpringBoot-TaskByTask/
├── src/main/java/com/eyelesscode/task_by_task/
│   ├── shared/
│   │   ├── security/
│   │   │   ├── SecurityConfig.java                                   # [MODIFICAR] Permitir rutas /api/v1/auth/** públicas
│   │   │   └── SecurityUtils.java                                    # [CREAR] Helper puro para extraer userId del SecurityContext
│   ├── user/
│   │   ├── User.java                                                 # [CREAR] Entidad JPA mapeada 1:1 a tabla `users` (ddl-auto=validate)
│   │   ├── UserRepository.java                                       # [CREAR] Spring Data JPA repository (búsquedas case-insensitive)
│   │   ├── UserService.java                                          # [CREAR] Lógica de perfil, contraseña, regeneración y borrado
│   │   ├── UserController.java                                       # [CREAR] REST controller bajo /api/v1/users/me/**
│   │   ├── UserMapper.java                                           # [CREAR] Mapper manual puro User -> UserResponse
│   │   └── dto/
│   │       ├── UserResponse.java                                     # [CREAR] record: id, email, username, displayName, timezone, timeFormat, createdAt, remainingRecoveryCodes
│   │       ├── UpdateProfileRequest.java                             # [CREAR] record: displayName, username, email, timezone, timeFormat
│   │       ├── ChangePasswordRequest.java                            # [CREAR] record: currentPassword, newPassword
│   │       ├── RegenerateRecoveryCodesRequest.java                   # [CREAR] record: currentPassword
│   │       ├── DeleteAccountRequest.java                             # [CREAR] record: password
│   │       └── RecoveryCodesResponse.java                            # [CREAR] record: recoveryCodes
│   ├── auth/
│   │   ├── RecoveryCode.java                                         # [CREAR] Entidad JPA mapeada a tabla `recovery_codes`
│   │   ├── RecoveryCodeRepository.java                               # [CREAR] Spring Data JPA repository para códigos
│   │   ├── RecoveryCodeGenerator.java                                # [CREAR] Función pura / generador de 8 códigos ^[A-Z0-9]{10}$
│   │   ├── AuthService.java                                          # [CREAR] Lógica de registro, login, logout, recuperación y timing-mitigation
│   │   ├── AuthController.java                                       # [CREAR] REST controller bajo /api/v1/auth/**
│   │   └── dto/
│   │       ├── RegisterRequest.java                                  # [CREAR] record: email, username, displayName, password, timezone
│   │       ├── RegisterResponse.java                                 # [CREAR] record: user (UserResponse), recoveryCodes (List<String>)
│   │       ├── LoginRequest.java                                     # [CREAR] record: email, password, timezone
│   │       ├── LogoutRequest.java                                    # [CREAR] record: endpoint (opcional)
│   │       ├── RecoverPasswordRequest.java                           # [CREAR] record: email, recoveryCode, newPassword
│   │       └── MessageResponse.java                                  # [CREAR] record: message (para respuestas 200 OK con mensaje)
│   ├── group/
│   │   ├── Group.java                                                # [CREAR] Entidad JPA mapeada a tabla `groups` (necesaria para grupo Personal)
│   │   ├── GroupMembership.java                                      # [CREAR] Entidad JPA mapeada a `group_memberships` (soporte RNF-20)
│   │   ├── GroupRepository.java                                      # [CREAR] Spring Data JPA repository para groups
│   │   └── GroupMembershipRepository.java                            # [CREAR] Spring Data JPA repository para memberships
│   └── notification/
│       ├── PushSubscription.java                                     # [CREAR] Entidad JPA mapeada a `push_subscriptions`
│       └── PushSubscriptionRepository.java                           # [CREAR] Spring Data JPA repository con deleteByUserIdAndEndpoint
└── src/test/java/com/eyelesscode/task_by_task/
    ├── auth/
    │   ├── RecoveryCodeGeneratorTest.java                            # [CREAR] Pruebas unitarias de formato y entropía ^[A-Z0-9]{10}$
    │   ├── AuthRegistrationIntegrationTest.java                      # [CREAR] Pruebas de registro atómico, colisiones, grupo Personal y sesión
    │   ├── AuthLoginLogoutIntegrationTest.java                       # [CREAR] Pruebas de login, cookie SESSION, timezone update, rate limit y logout
    │   └── AuthPasswordRecoveryIntegrationTest.java                  # [CREAR] Pruebas de recuperación con código, timing check, consumo y revocación
    └── user/
        ├── UserProfileIntegrationTest.java                           # [CREAR] Pruebas de GET/PATCH perfil, validación de time_format y unicidad
        ├── UserPasswordAndRecoveryCodesIntegrationTest.java          # [CREAR] Pruebas de cambio de clave (sesión activa preservada) y regeneración
        └── UserAccountDeletionIntegrationTest.java                   # [CREAR] Pruebas de borrado en cascada, verificación de clave y cancelación de jobs
```

---

## 3. Decisiones de Diseño y Alternativas Descartadas

### 3.1 Identificador de Sesión en Spring Session JDBC (`PRINCIPAL_NAME`)
- **Decisión:** Registrar el ID numérico inmutable del usuario (`String.valueOf(user.getId())`) como `PRINCIPAL_NAME` en la sesión de Spring Session JDBC (`findByIndexNameSessionRepository`).
- **Alternativa Descartada:** Usar el `email` o `username` del usuario. Si el usuario actualiza su email o username en `PATCH /api/v1/users/me`, el índice `SPRING_SESSION_IX3` quedaría desfasado con respecto a la entidad `User`, provocando que las consultas de invalidación masiva (`findByPrincipalName`) fallen o queden huérfanas.
- **Requisitos cubiertos:** `AUTH-REQ-07`, `AUTH-REQ-08`, `AUTH-REQ-13`, `AUTH-REQ-18`, `AUTH-REQ-21`.

### 3.2 Política de Revocación de Sesiones: Cambio vs Recuperación
- **Decisión:**
  - En recuperación (`POST /api/v1/auth/recover-password`): Invalidación absoluta de todas las sesiones del usuario en `SPRING_SESSION` (`sessionRepository.deleteById(...)`).
  - En cambio voluntario (`POST /api/v1/users/me/password`): Obtener todas las sesiones del usuario vía `findByPrincipalName(userId)`, iterar e invalidar únicamente aquellas cuyo `sessionId` difiera del `currentSessionId` de la petición HTTP actual.
- **Alternativa Descartada:** Forzar cierre de sesión en el dispositivo que realiza el cambio voluntario. Esto degrada la experiencia del usuario legítimo en su propio dispositivo sin beneficio de seguridad adicional.
- **Requisitos cubiertos:** `AUTH-REQ-13`, `AUTH-REQ-21`, `EC-06`.

### 3.3 Mitigación de Canales Laterales de Tiempo (*Timing Attacks*) en Recuperación
- **Decisión:** Mantener una constante estática de hash BCrypt precalculada (`DUMMY_BCRYPT_HASH`). Cuando una solicitud de recuperación invoque un correo inexistente, el sistema ejecuta de inmediato `passwordEncoder.matches(normalizedCode, DUMMY_BCRYPT_HASH)` antes de arrojar `BadRequestException`.
- **Alternativa Descartada:** Retornar inmediatamente el error 400. Esto permitiría a atacantes discernir si un correo existe o no midiendo si la respuesta tarda ~1ms (correo no existe) o ~100ms (cálculo de verificación BCrypt contra los hashes de la base de datos).
- **Requisitos cubiertos:** `AUTH-REQ-14`, `EC-12`.

### 3.4 Invariante de 8 Códigos de Recuperación en Regeneración
- **Decisión:** Ejecutar una sentencia de eliminación física `DELETE FROM recovery_codes WHERE user_id = :userId` antes de persistir los 8 nuevos hashes generados.
- **Alternativa Descartada:** Marcar los códigos previos con un flag `invalidated = true` o simplemente agregar 8 más. El esquema relacional V1 y el SRS (Apéndice B) establecen un límite estricto de 8 códigos por usuario. Dejar filas obsoletas inflaría la tabla y complicaría el conteo de códigos activos.
- **Requisitos cubiertos:** `AUTH-REQ-23`, Apéndice B.

### 3.5 Eliminación de Suscripciones Push en Logout Desacoplada
- **Decisión:** Si el cliente envía opcionalmente `{"endpoint": "..."}` en el cuerpo de `POST /api/v1/auth/logout`, `AuthService` invoca `pushSubscriptionRepository.deleteByUserIdAndEndpoint(user.getId(), endpoint)`. Si se omite, el logout procede con la invalidación de la sesión sin error.
- **Alternativa Descartada:** Depender del módulo de notificaciones completas (`004-notifications`) para el logout. El desacoplamiento permite que `001-auth-and-users` cumpla la regla de `RF-02` sin introducir lógica de cifrado Web Push ni claves VAPID prematuras.
- **Requisitos cubiertos:** `AUTH-REQ-12`, `EC-09`.

---

## 4. Diseño de Entidades JPA (`ddl-auto=validate`)

Para cumplir con el principio de validación estricta de Hibernate frente a `V1__init_schema.sql`, las entidades se diseñan con mapeos de tipos exactos:

### 4.1 `com.eyelesscode.task_by_task.user.User`
```java
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false, length = 30)
    private String username;

    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String timezone;

    @Column(name = "time_format", nullable = false, length = 3)
    private String timeFormat;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    
    // Constructores, getters y setters explícitos (cero Lombok)
}
```

### 4.2 `com.eyelesscode.task_by_task.auth.RecoveryCode`
```java
@Entity
@Table(name = "recovery_codes")
public class RecoveryCode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "code_hash", nullable = false, length = 255)
    private String codeHash;

    @Column(nullable = false)
    private boolean used;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
```

### 4.3 `com.eyelesscode.task_by_task.group.Group` y `GroupMembership`
```java
@Entity
@Table(name = "groups")
public class Group {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    @Column(nullable = false, length = 30)
    private String name;

    @Column(name = "is_personal", nullable = false)
    private boolean isPersonal;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

@Entity
@Table(name = "group_memberships")
public class GroupMembership {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 20)
    private String role; // 'CREATOR', 'MANAGER', 'VISITOR'

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;
}
```

### 4.4 `com.eyelesscode.task_by_task.notification.PushSubscription`
```java
@Entity
@Table(name = "push_subscriptions")
public class PushSubscription {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String endpoint;

    @Column(nullable = false, length = 255)
    private String p256dh;

    @Column(nullable = false, length = 255)
    private String auth;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
```

---

## 5. Funciones Puras y Componentes Auxiliares

### 5.1 Generador de Códigos de Recuperación (`RecoveryCodeGenerator`)
Función pura e inmutable que genera 8 códigos de recuperación de 10 caracteres alfanuméricos en mayúsculas utilizando `SecureRandom`:
```java
public final class RecoveryCodeGenerator {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 10;
    private static final int CODE_COUNT = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static List<String> generateCodes() {
        Set<String> codes = new LinkedHashSet<>(CODE_COUNT);
        while (codes.size() < CODE_COUNT) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            codes.add(sb.toString());
        }
        return List.copyOf(codes);
    }
    
    public static String normalizeCode(String code) {
        if (code == null) return "";
        return code.trim().toUpperCase();
    }
}
```

### 5.2 Helper de Seguridad (`SecurityUtils`)
Extracción determinista y desacoplada del ID de usuario autenticado:
```java
public final class SecurityUtils {
    public static Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new AccessDeniedException("No autenticado");
        }
        try {
            return Long.valueOf(auth.getName());
        } catch (NumberFormatException e) {
            throw new AccessDeniedException("Identificador de usuario inválido en sesión");
        }
    }
}
```

---

## 6. Flujos de Negocio y Transaccionalidad

### 6.1 Registro de Usuario (`POST /api/v1/auth/register`)
1. Rate limit: `rateLimitService.consumeOrThrow(clientIp + ":register")`.
2. Validaciones de DTO: sintaxis de email, regex `^[A-Za-z0-9]{3,30}$` en username, display name 2-50, password 6-30 chars, timezone IANA (si omitted, `UTC`).
3. Normalización: `email = email.trim().toLowerCase()`, `username = username.trim()`.
4. Comprobación de unicidad:
   - `userRepository.existsByEmailIgnoreCase(email)` -> `ResourceConflictException("El correo electrónico ya está registrado")`.
   - `userRepository.existsByUsernameIgnoreCase(username)` -> `ResourceConflictException("El nombre de usuario ya está en uso")`.
5. Transacción atómica (`@Transactional`):
   - Crear y guardar `User` (password hasheada con `passwordEncoder.encode(password)`).
   - Crear y guardar `Group` ("Personal", `is_personal = true`, `creator = user`).
   - Crear y guardar `GroupMembership` (`role = 'CREATOR'`, `group = personalGroup`, `user = user`).
   - Generar 8 códigos con `RecoveryCodeGenerator.generateCodes()`.
   - Hashear cada código con `passwordEncoder.encode(code)` y persistir en `recovery_codes` con `used = false`.
6. Establecer sesión HTTP:
   - Crear `UsernamePasswordAuthenticationToken` con principal `String.valueOf(user.getId())`.
   - Almacenar en `SecurityContextHolder` y en `HttpSession` bajo `SPRING_SECURITY_CONTEXT_KEY`.
   - Establecer atributo `FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME = String.valueOf(user.getId())`.
7. Responder `HTTP 201 Created` con `RegisterResponse` conteniendo `UserResponse` y los 8 códigos en texto plano.

### 6.2 Inicio de Sesión (`POST /api/v1/auth/login`)
1. Rate limit: `rateLimitService.consumeOrThrow(clientIp + ":login")`.
2. Buscar usuario por `userRepository.findByEmailIgnoreCase(email.trim().toLowerCase())`.
3. Si no existe o `!passwordEncoder.matches(password, user.getPasswordHash())`:
   - Lanzar `RestAuthenticationException` / error controlado que se mapee a `HTTP 401 Unauthorized` (`title: "Credenciales inválidas"`, `detail: "El correo electrónico o la contraseña son incorrectos"`).
4. Si se proporcionó `timezone`:
   - Validar con `ZoneId.of(timezone)`. Si falla -> `BadRequestException("La zona horaria proporcionada no es válida")`.
   - Si cambió, actualizar `user.setTimezone(timezone)` y guardar.
5. Iniciar sesión:
   - Invalidar sesión HTTP anterior si existiera y crear una nueva (`request.getSession(true)`).
   - Asignar `SecurityContext` con `principal = String.valueOf(user.getId())`.
   - Asignar `PRINCIPAL_NAME_INDEX_NAME = String.valueOf(user.getId())`.
6. Contar códigos restantes: `recoveryCodeRepository.countByUserIdAndUsedFalse(user.getId())`.
7. Responder `HTTP 200 OK` con `UserResponse`.

### 6.3 Cierre de Sesión (`POST /api/v1/auth/logout`)
1. Obtener usuario autenticado vía `SecurityUtils.getCurrentUserId()`.
2. Si la petición incluye `endpoint` en `LogoutRequest`:
   - Ejecutar `pushSubscriptionRepository.deleteByUserIdAndEndpoint(userId, endpoint)`.
3. Invalidar la sesión actual (`request.getSession(false).invalidate()`) y limpiar `SecurityContextHolder.clearContext()`.
4. Responder `HTTP 204 No Content`.

### 6.4 Recuperación de Contraseña (`POST /api/v1/auth/recover-password`)
1. Rate limit: `rateLimitService.consumeOrThrow(clientIp + ":recover-password")`.
2. Validar longitud de nueva contraseña (6-30 caracteres).
3. Buscar usuario por `userRepository.findByEmailIgnoreCase(email.trim().toLowerCase())`.
4. **Mitigación de timing attack:**
   - Si el usuario no existe: ejecutar `passwordEncoder.matches("DUMMY_CODE", DUMMY_BCRYPT_HASH)` y arrojar `BadRequestException("El correo electrónico o el código de recuperación son incorrectos, o el código ya fue utilizado")`.
5. Normalizar código con `RecoveryCodeGenerator.normalizeCode(recoveryCode)`.
6. Cargar códigos no usados del usuario: `recoveryCodeRepository.findByUserIdAndUsedFalse(user.getId())`.
7. Iterar y comparar con `passwordEncoder.matches(normalizedCode, code.getCodeHash())`.
8. Si ninguno coincide: arrojar `BadRequestException("El correo electrónico o el código de recuperación son incorrectos, o el código ya fue utilizado")`.
9. Si coincide:
   - `@Transactional`:
     - Marcar código como `used = true`, `usedAt = Instant.now()`.
     - Actualizar contraseña del usuario con `passwordEncoder.encode(newPassword)`.
     - Revocar **todas** las sesiones activas del usuario:
       ```java
       Map<String, ? extends Session> sessions = sessionRepository.findByPrincipalName(String.valueOf(user.getId()));
       sessions.keySet().forEach(sessionRepository::deleteById);
       ```
10. Responder `HTTP 200 OK` con `MessageResponse("Contraseña restablecida exitosamente")`.

### 6.5 Cambio de Contraseña (`POST /api/v1/users/me/password`)
1. Obtener `userId = SecurityUtils.getCurrentUserId()`.
2. Validar `currentPassword` contra el hash del usuario. Si falla -> `BadRequestException("La contraseña actual ingresada es incorrecta")`.
3. Validar longitud de `newPassword` (6 a 30 caracteres).
4. `@Transactional`:
   - Actualizar hash de la contraseña en `User`.
   - Obtener `currentSessionId = request.getSession().getId()`.
   - Obtener todas las sesiones de `String.valueOf(userId)` en `sessionRepository`.
   - Invalidar todas aquellas donde `!session.getId().equals(currentSessionId)`.
5. Responder `HTTP 200 OK` con `MessageResponse("Contraseña actualizada exitosamente")`.

### 6.6 Regeneración de Códigos (`POST /api/v1/users/me/recovery-codes/regenerate`)
1. Obtener `userId = SecurityUtils.getCurrentUserId()`.
2. Validar `currentPassword`. Si falla -> `BadRequestException("La contraseña actual ingresada es incorrecta")`.
3. `@Transactional`:
   - Eliminar físicamente todos los códigos previos: `recoveryCodeRepository.deleteByUserId(userId)`.
   - Generar 8 nuevos códigos con `RecoveryCodeGenerator.generateCodes()`.
   - Hashear e insertar los 8 códigos con `used = false`.
4. Responder `HTTP 200 OK` con `RecoveryCodesResponse(codes)`.

### 6.7 Eliminación Definitiva de Cuenta (`DELETE /api/v1/users/me`)
1. Obtener `userId = SecurityUtils.getCurrentUserId()`.
2. Validar `password`. Si falla -> `BadRequestException("La contraseña ingresada es incorrecta")`.
3. `@Transactional`:
   - Cancelar trabajos en el scheduler para tareas del usuario:
     ```java
     // Buscar IDs de tareas pertenecientes a grupos del usuario y eliminarlas de scheduled_tasks
     jdbcTemplate.update(
         "DELETE FROM scheduled_tasks WHERE task_instance IN (" +
         "  SELECT 'task-due-' || t.id FROM tasks t JOIN groups g ON t.group_id = g.id WHERE g.creator_id = ?" +
         ")", userId);
     ```
   - Invalidar todas las sesiones del usuario en `sessionRepository`.
   - Eliminar el registro del usuario en `userRepository.delete(user)` (la base de datos ejecuta `ON DELETE CASCADE` para groups, group_memberships, recovery_codes, push_subscriptions, notifications, tasks y tags).
4. Limpiar contexto de seguridad y responder `HTTP 204 No Content`.

---

## 7. Estrategia de Pruebas Automatizadas

Las pruebas se ejecutarán contra PostgreSQL real instanciado mediante Testcontainers (`postgres:alpine`) heredando de `AbstractIntegrationTest`, con `ddl-auto=validate`:

1. **`RecoveryCodeGeneratorTest` (Unitario):**
   - Verifica que se generen exactamente 8 códigos únicos de longitud 10.
   - Verifica que cumplan la expresión regular `^[A-Z0-9]{10}$`.
   - Verifica la normalización `TRIM(UPPER(code))`.
2. **`AuthRegistrationIntegrationTest` (Integración):**
   - Registro exitoso con 201 Created, cookie SESSION y 8 códigos.
   - Verificación de creación en base de datos del grupo "Personal" (`is_personal = true`) y membresía `CREATOR`.
   - Rechazo 409 ante duplicación de correo (insensible a mayúsculas).
   - Rechazo 409 ante duplicación de username (insensible a mayúsculas).
   - Rechazo 400 ante validaciones de longitud y caracteres inválidos en username.
3. **`AuthLoginLogoutIntegrationTest` (Integración):**
   - Login exitoso con credenciales válidas y retorno de cookie SESSION.
   - Actualización exitosa de zona horaria IANA al iniciar sesión.
   - Rechazo 400 ante zona horaria no reconocida por IANA sin crear sesión.
   - Rechazo 401 con mensaje genérico ante contraseña incorrecta o correo inexistente.
   - Rate limiting: 5 intentos fallidos disparan HTTP 429 con cabecera `Retry-After`.
   - Logout: invalidación de sesión y eliminación de suscripción push coincidente.
4. **`AuthPasswordRecoveryIntegrationTest` (Integración):**
   - Recuperación exitosa con código de un solo uso y nueva contraseña.
   - Verificación de que el código utilizado pasa a `used = true` y no puede volver a usarse.
   - Verificación de revocación de sesiones previas en `SPRING_SESSION`.
   - Rechazo 400 ante correo inexistente ejecutando mitigación de timing attack.
5. **`UserProfileIntegrationTest` (Integración):**
   - `GET /api/v1/users/me` retorna datos del usuario y conteo de códigos (0 a 8).
   - `PATCH /api/v1/users/me` actualiza display name, username, email y timezone.
   - `PATCH` validación estricta de `time_format` (solo admite `24H` y `12H`, 400 ante otros).
   - `PATCH` no genera conflicto 409 consigo mismo.
6. **`UserPasswordAndRecoveryCodesIntegrationTest` (Integración):**
   - Cambio de clave exitoso invalidando otras sesiones y manteniendo la actual.
   - Rechazo 400 ante clave actual incorrecta en cambio de clave.
   - Regeneración de códigos: eliminación física de los anteriores y entrega de 8 nuevos.
7. **`UserAccountDeletionIntegrationTest` (Integración):**
   - Eliminación exitosa de cuenta: borrado en cascada de grupos, membresías y sesiones.
   - Cancelación de jobs en `scheduled_tasks`.
   - Rechazo 400 ante contraseña incorrecta preservando la cuenta.

---

## 8. Matriz de Cobertura de Requerimientos

| Requisito EARS / Caso Borde | Componente / Archivo de Implementación | Clase de Prueba de Verificación |
| :--- | :--- | :--- |
| **AUTH-REQ-01** a **04** | `AuthService.register()`, `User`, `RegisterRequest` | `AuthRegistrationIntegrationTest` |
| **AUTH-REQ-05** | `AuthService.register()`, `Group`, `GroupMembership` | `AuthRegistrationIntegrationTest` |
| **AUTH-REQ-06** | `RecoveryCodeGenerator`, `RecoveryCode`, `RecoveryCodeRepository` | `RecoveryCodeGeneratorTest`, `AuthRegistrationIntegrationTest` |
| **AUTH-REQ-07** | `AuthService.register()`, `SecurityConfig` | `AuthRegistrationIntegrationTest` |
| **AUTH-REQ-08** a **11** | `AuthService.login()`, `RateLimitService`, `LoginRequest` | `AuthLoginLogoutIntegrationTest` |
| **AUTH-REQ-12** | `AuthService.logout()`, `PushSubscriptionRepository` | `AuthLoginLogoutIntegrationTest` |
| **AUTH-REQ-13** a **16** | `AuthService.recoverPassword()`, `RecoveryCodeRepository` | `AuthPasswordRecoveryIntegrationTest` |
| **AUTH-REQ-17** | `UserService.getProfile()`, `UserController` | `UserProfileIntegrationTest` |
| **AUTH-REQ-18** a **20** | `UserService.updateProfile()`, `UpdateProfileRequest` | `UserProfileIntegrationTest` |
| **AUTH-REQ-21**, **22** | `UserService.changePassword()`, `ChangePasswordRequest` | `UserPasswordAndRecoveryCodesIntegrationTest` |
| **AUTH-REQ-23**, **24** | `UserService.regenerateRecoveryCodes()`, `RecoveryCodeRepository` | `UserPasswordAndRecoveryCodesIntegrationTest` |
| **AUTH-REQ-25**, **26** | `UserService.deleteAccount()`, `DeleteAccountRequest` | `UserAccountDeletionIntegrationTest` |
| **AUTH-REQ-27**, **28** | `RateLimitService`, `AuthController` | `AuthLoginLogoutIntegrationTest` |
| **AUTH-REQ-29**, **30** | `GlobalExceptionHandler`, `SecurityConfig` | `UserProfileIntegrationTest`, `AuthLoginLogoutIntegrationTest` |
| **EC-01**, **EC-02** | `AuthService.register()`, `UserService.updateProfile()` | `AuthRegistrationIntegrationTest`, `UserProfileIntegrationTest` |
| **EC-03**, **EC-04**, **EC-05** | `AuthService.recoverPassword()` | `AuthPasswordRecoveryIntegrationTest` |
| **EC-06** | `AuthService.recoverPassword()`, `UserService.changePassword()` | `AuthPasswordRecoveryIntegrationTest`, `UserPasswordAndRecoveryCodesIntegrationTest` |
| **EC-07** | `AuthService.register()`, `AuthService.login()` | `AuthLoginLogoutIntegrationTest` |
| **EC-08** | `UserService.deleteAccount()` (limpieza en `scheduled_tasks`) | `UserAccountDeletionIntegrationTest` |
| **EC-09** | `AuthService.logout()` con `endpoint` opcional | `AuthLoginLogoutIntegrationTest` |
| **EC-10** | `UserService.changePassword()`, `UserService.deleteAccount()` | `UserPasswordAndRecoveryCodesIntegrationTest`, `UserAccountDeletionIntegrationTest` |
| **EC-11** | `RecoveryCodeGenerator.normalizeCode()` | `RecoveryCodeGeneratorTest`, `AuthPasswordRecoveryIntegrationTest` |
| **EC-12** | `AuthService.recoverPassword()` (dummy BCrypt verification) | `AuthPasswordRecoveryIntegrationTest` |
