# Lista de Tareas: 001-auth-and-users (Autenticación y Gestión de Usuarios)

**Fase:** 001-auth-and-users  
**Estado:** pending (lista de tareas desglosada y lista para ejecución secuencial)  
**Fecha:** 2026-11-10  
**Autor:** SDD Planner Agent  
**Referencias:**
- `docs/specs/001-auth-and-users/spec.md` (AUTH-REQ-01 a AUTH-REQ-30, EC-01 a EC-12)
- `docs/specs/001-auth-and-users/plan.md` (Diseño técnico y arquitectura)
- `docs/constitution.md` (Principios 1 a 6)
- `AGENTS.md` (Comandos de verificación y domain rules)
- `src/main/resources/db/migration/V1__init_schema.sql` (Esquema DDL de referencia)

---

## Tareas de Implementación

- [x] **T1: Entidades JPA base y Repositorios (`user`, `auth`, `group`, `notification`) con validación Hibernate**
  - **Requisitos cubiertos:** AUTH-REQ-01, AUTH-REQ-05, AUTH-REQ-06, AUTH-REQ-12, RNF-20, Constitución Principio 6.
  - **Archivos a crear:**
    - `src/main/java/com/eyelesscode/task_by_task/user/User.java`
    - `src/main/java/com/eyelesscode/task_by_task/user/UserRepository.java`
    - `src/main/java/com/eyelesscode/task_by_task/auth/RecoveryCode.java`
    - `src/main/java/com/eyelesscode/task_by_task/auth/RecoveryCodeRepository.java`
    - `src/main/java/com/eyelesscode/task_by_task/group/Group.java`
    - `src/main/java/com/eyelesscode/task_by_task/group/GroupRepository.java`
    - `src/main/java/com/eyelesscode/task_by_task/group/GroupMembership.java`
    - `src/main/java/com/eyelesscode/task_by_task/group/GroupMembershipRepository.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/PushSubscription.java`
    - `src/main/java/com/eyelesscode/task_by_task/notification/PushSubscriptionRepository.java`
  - **Descripción:** Implementar las entidades JPA bajo Pure Java 21 (sin Lombok ni anotaciones generadoras) alineadas 1:1 con `V1__init_schema.sql` para que `ddl-auto=validate` pase limpiamente. Definir los repositorios Spring Data JPA correspondientes con consultas para búsquedas case-insensitive por email/username, conteo y borrado físico de códigos de recuperación, y eliminación de push subscription por endpoint.
  - **Criterio de finalización (Done when):** `./mvnw test -Dtest=ApplicationIntegrationTest` ejecuta contra Testcontainers PostgreSQL y valida que Hibernate arranca sin errores de validación de esquema (`ddl-auto=validate`).

- [x] **T2: Funciones puras de utilidad: Generador de Códigos (`RecoveryCodeGenerator`) y `SecurityUtils`**
  - **Requisitos cubiertos:** AUTH-REQ-06, AUTH-REQ-13, EC-11.
  - **Archivos a crear:**
    - `src/main/java/com/eyelesscode/task_by_task/auth/RecoveryCodeGenerator.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/security/SecurityUtils.java`
    - `src/test/java/com/eyelesscode/task_by_task/auth/RecoveryCodeGeneratorTest.java`
  - **Descripción:**
    - Implementar `RecoveryCodeGenerator` con funciones estáticas puras: `generateCodes()` generando 8 códigos únicos de longitud 10 en mayúsculas `^[A-Z0-9]{10}$` usando `SecureRandom`, y `normalizeCode(code)` aplicando `TRIM(UPPER())`.
    - Implementar `SecurityUtils.getCurrentUserId()` extrayendo el ID de usuario numérico desde el principal de Spring Security.
    - Implementar prueba unitaria `RecoveryCodeGeneratorTest` validando entropía, unicidad, formato y normalización.
  - **Criterio de finalización (Done when):** `./mvnw test -Dtest=RecoveryCodeGeneratorTest` pasa al 100% y `./mvnw spotless:check` es exitoso.

- [x] **T3: DTOs inmutables de autenticación y registro atómico (`AuthService.register`)**
  - **Requisitos cubiertos:** AUTH-REQ-01, AUTH-REQ-02, AUTH-REQ-03, AUTH-REQ-04, AUTH-REQ-05, AUTH-REQ-06, AUTH-REQ-07, EC-01, RNF-20.
  - **Archivos a crear/modificar:**
    - `src/main/java/com/eyelesscode/task_by_task/shared/security/SecurityConfig.java` (permitir `/api/v1/auth/register`, `/api/v1/auth/login`, `/api/v1/auth/recover-password`)
    - `src/main/java/com/eyelesscode/task_by_task/user/dto/UserResponse.java`
    - `src/main/java/com/eyelesscode/task_by_task/auth/dto/RegisterRequest.java`
    - `src/main/java/com/eyelesscode/task_by_task/auth/dto/RegisterResponse.java`
    - `src/main/java/com/eyelesscode/task_by_task/auth/dto/MessageResponse.java`
    - `src/main/java/com/eyelesscode/task_by_task/auth/AuthService.java`
    - `src/main/java/com/eyelesscode/task_by_task/auth/AuthController.java`
    - `src/test/java/com/eyelesscode/task_by_task/auth/AuthRegistrationIntegrationTest.java`
  - **Descripción:**
    - Actualizar `SecurityConfig` para exponer las rutas `/api/v1/auth/**` públicas.
    - Implementar `AuthService.register()` de forma transaccional: validaciones de entrada, unicidad case-insensitive (`409 Conflict`), persistencia de `User` con `{bcrypt}`, creación atómica del grupo `"Personal"` (`is_personal = true`) con membresía `CREATOR`, generación y persistencia de 8 códigos de recuperación con `{bcrypt}`, creación de sesión en `SPRING_SESSION` con `PRINCIPAL_NAME = String.valueOf(user.id)`, y retorno de 201 Created.
    - Implementar endpoint `POST /api/v1/auth/register` en `AuthController`.
    - Crear `AuthRegistrationIntegrationTest` con pruebas completas de registro exitoso, grupo personal creado, 8 códigos recibidos, y rechazo 409 por email o username duplicado.
  - **Criterio de finalización (Done when):** `./mvnw test -Dtest=AuthRegistrationIntegrationTest` pasa al 100%.

- [ ] **T4: Inicio y cierre de sesión con rate limiting y cookie persistente (`AuthService.login`, `AuthService.logout`)**
  - **Requisitos cubiertos:** AUTH-REQ-08, AUTH-REQ-09, AUTH-REQ-10, AUTH-REQ-11, AUTH-REQ-12, AUTH-REQ-27, AUTH-REQ-28, EC-07, EC-09, RNF-06, RNF-09.
  - **Archivos a crear/modificar:**
    - `src/main/java/com/eyelesscode/task_by_task/auth/dto/LoginRequest.java`
    - `src/main/java/com/eyelesscode/task_by_task/auth/dto/LogoutRequest.java`
    - `src/main/java/com/eyelesscode/task_by_task/auth/AuthService.java`
    - `src/main/java/com/eyelesscode/task_by_task/auth/AuthController.java`
    - `src/test/java/com/eyelesscode/task_by_task/auth/AuthLoginLogoutIntegrationTest.java`
  - **Descripción:**
    - Implementar `login()` con rate limiting (`RateLimitService`), validación de credenciales (retornando 401 genérico ante fallo), validación y actualización de zona horaria IANA (400 si es inválida), establecimiento de sesión persistente con cookie `SESSION` (7 días) y `PRINCIPAL_NAME = user.id`.
    - Implementar `logout()` destruyendo la sesión HTTP activa y eliminando selectivamente la suscripción push en `push_subscriptions` si se proporciona `endpoint`.
    - Implementar endpoints `POST /api/v1/auth/login` y `POST /api/v1/auth/logout`.
    - Crear `AuthLoginLogoutIntegrationTest` verificando login exitoso, 401 genérico, rechazo por zona horaria inválida, rate limit 429 tras 5 intentos fallidos, y logout con eliminación de suscripción push.
  - **Criterio de finalización (Done when):** `./mvnw test -Dtest=AuthLoginLogoutIntegrationTest` pasa al 100%.

- [ ] **T5: Recuperación de contraseña con códigos de un solo uso y mitigación de timing attack (`AuthService.recoverPassword`)**
  - **Requisitos cubiertos:** AUTH-REQ-13, AUTH-REQ-14, AUTH-REQ-15, AUTH-REQ-16, EC-03, EC-04, EC-05, EC-06, EC-11, EC-12, RNF-05.
  - **Archivos a crear/modificar:**
    - `src/main/java/com/eyelesscode/task_by_task/auth/dto/RecoverPasswordRequest.java`
    - `src/main/java/com/eyelesscode/task_by_task/auth/AuthService.java`
    - `src/main/java/com/eyelesscode/task_by_task/auth/AuthController.java`
    - `src/test/java/com/eyelesscode/task_by_task/auth/AuthPasswordRecoveryIntegrationTest.java`
  - **Descripción:**
    - Implementar `recoverPassword()` con rate limiting, verificación dummy de BCrypt ante correo no registrado para mitigar timing attacks, normalización `TRIM(UPPER(code))`, verificación contra códigos no usados, consumo atómico del código (`used = true`), actualización de hash de contraseña, invalidación masiva de **todas** las sesiones activas en `SPRING_SESSION`, y respuesta genérica 400 sin filtrar datos.
    - Implementar endpoint `POST /api/v1/auth/recover-password`.
    - Crear `AuthPasswordRecoveryIntegrationTest` verificando flujo exitoso, código consumido no reusable, revocación total de sesiones y mitigación de canal lateral ante correos inexistentes.
  - **Criterio de finalización (Done when):** `./mvnw test -Dtest=AuthPasswordRecoveryIntegrationTest` pasa al 100%.

- [ ] **T6: Consulta y actualización de perfil y preferencias (`UserService.getProfile`, `UserService.updateProfile`)**
  - **Requisitos cubiertos:** AUTH-REQ-17, AUTH-REQ-18, AUTH-REQ-19, AUTH-REQ-20, AUTH-REQ-30, EC-01, EC-02, RNF-14.
  - **Archivos a crear:**
    - `src/main/java/com/eyelesscode/task_by_task/user/dto/UpdateProfileRequest.java`
    - `src/main/java/com/eyelesscode/task_by_task/user/UserMapper.java`
    - `src/main/java/com/eyelesscode/task_by_task/user/UserService.java`
    - `src/main/java/com/eyelesscode/task_by_task/user/UserController.java`
    - `src/test/java/com/eyelesscode/task_by_task/user/UserProfileIntegrationTest.java`
  - **Descripción:**
    - Implementar `getProfile()` retornando datos del usuario autenticado y conteo de códigos de recuperación no usados (0 a 8).
    - Implementar `updateProfile()` soportando actualización parcial (`PATCH`), validación estricta de `time_format` (`24H` o `12H`), verificación de unicidad sin colisionar consigo mismo, y preservación de `PRINCIPAL_NAME = user.id`.
    - Implementar endpoints `GET /api/v1/users/me` y `PATCH /api/v1/users/me`.
    - Crear `UserProfileIntegrationTest` verificando consulta de perfil, actualización parcial, 400 ante time_format inválido, 409 ante email/username ocupado por otro usuario, y actualización sin conflicto con el propio valor.
  - **Criterio de finalización (Done when):** `./mvnw test -Dtest=UserProfileIntegrationTest` pasa al 100%.

- [ ] **T7: Cambio de contraseña y regeneración de códigos (`UserService.changePassword`, `UserService.regenerateRecoveryCodes`)**
  - **Requisitos cubiertos:** AUTH-REQ-21, AUTH-REQ-22, AUTH-REQ-23, AUTH-REQ-24, EC-06, EC-10, RNF-05, RNF-09.
  - **Archivos a crear/modificar:**
    - `src/main/java/com/eyelesscode/task_by_task/user/dto/ChangePasswordRequest.java`
    - `src/main/java/com/eyelesscode/task_by_task/user/dto/RegenerateRecoveryCodesRequest.java`
    - `src/main/java/com/eyelesscode/task_by_task/user/dto/RecoveryCodesResponse.java`
    - `src/main/java/com/eyelesscode/task_by_task/user/UserService.java`
    - `src/main/java/com/eyelesscode/task_by_task/user/UserController.java`
    - `src/test/java/com/eyelesscode/task_by_task/user/UserPasswordAndRecoveryCodesIntegrationTest.java`
  - **Descripción:**
    - Implementar `changePassword()` exigiendo contraseña actual, actualizando a nuevo hash `{bcrypt}`, invalidando todas las *demás* sesiones activas en la base de datos mientras se preserva la sesión activa actual.
    - Implementar `regenerateRecoveryCodes()` exigiendo contraseña actual, eliminando físicamente (`DELETE`) todos los códigos previos del usuario en `recovery_codes`, generando 8 nuevos códigos en mayúsculas y retornándolos una sola vez.
    - Implementar endpoints `POST /api/v1/users/me/password` y `POST /api/v1/users/me/recovery-codes/regenerate`.
    - Crear `UserPasswordAndRecoveryCodesIntegrationTest` validando cambio de contraseña, preservación de sesión activa actual, eliminación física y entrega de 8 nuevos códigos, y rechazo 400 ante contraseña actual incorrecta.
  - **Criterio de finalización (Done when):** `./mvnw test -Dtest=UserPasswordAndRecoveryCodesIntegrationTest` pasa al 100%.

- [ ] **T8: Eliminación definitiva de cuenta y cascada completa (`UserService.deleteAccount`)**
  - **Requisitos cubiertos:** AUTH-REQ-25, AUTH-REQ-26, EC-08, EC-10, RF-05, RNF-10.
  - **Archivos a crear/modificar:**
    - `src/main/java/com/eyelesscode/task_by_task/user/dto/DeleteAccountRequest.java`
    - `src/main/java/com/eyelesscode/task_by_task/user/UserService.java`
    - `src/main/java/com/eyelesscode/task_by_task/user/UserController.java`
    - `src/test/java/com/eyelesscode/task_by_task/user/UserAccountDeletionIntegrationTest.java`
  - **Descripción:**
    - Implementar `deleteAccount()` exigiendo contraseña del usuario (400 si es incorrecta), cancelando de forma transaccional todos los trabajos programados en `scheduled_tasks` para tareas de sus grupos, invalidando todas las sesiones en `SPRING_SESSION` y eliminando el registro del usuario (disparando cascada relacional para groups, memberships, recovery codes, push subscriptions y notifications).
    - Implementar endpoint `DELETE /api/v1/users/me`.
    - Crear `UserAccountDeletionIntegrationTest` verificando borrado en cascada, cancelación de jobs en `scheduled_tasks`, revocación de sesiones, y protección ante contraseña errónea.
  - **Criterio de finalización (Done when):** `./mvnw test -Dtest=UserAccountDeletionIntegrationTest` pasa al 100%.

- [ ] **T9: Verificación de calidad, formateo Spotless y suite completa de pruebas**
  - **Requisitos cubiertos:** Todos los requisitos del módulo, Constitución Principios 3 y 6.
  - **Archivos a verificar:** Todo el repositorio.
  - **Descripción:** Ejecutar `./mvnw spotless:apply` seguido de `./mvnw spotless:check compile test-compile`. Ejecutar `./mvnw test` asegurando que el 100% de las pruebas (unitarias e integración con Testcontainers) pasen limpiamente sin advertencias ni regresiones.
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test` finalizan con éxito (`BUILD SUCCESS`).
