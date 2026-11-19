# Plan Técnico de Implementación: 002-groups (Gestión de Grupos Individuales y Grupo "Personal")

**Fase:** 002-groups  
**Estado:** approved (basado en `spec.md` aprobada)  
**Fecha:** 2026-11-15  
**Autor:** SDD Planner Agent  
**Referencias:**
- `docs/specs/002-groups/spec.md` (GRP-REQ-01 a GRP-REQ-25, EC-01 a EC-12)
- `docs/specs/000-foundation/spec.md` y `plan.md` (Esquema V1, KeysetCursorCodec, GlobalExceptionHandler, SecurityConfig)
- `docs/specs/001-auth-and-users/spec.md` y `plan.md` (Entidades `Group`, `GroupMembership`, `SecurityUtils`)
- `docs/constitution.md` (Principios 1 a 6)
- `AGENTS.md` (Convenciones Pure Java 21, domain rules y comandos de verificación)
- `src/main/resources/db/migration/V1__init_schema.sql` (`groups`, `group_memberships`, `tasks`, `tags`, `task_tags`, `scheduled_tasks`)

---

## 1. Resumen Ejecutivo y Enfoque de Diseño

El objetivo del módulo **002-groups** es implementar la gestión completa del ciclo de vida de los grupos de tareas en el MVP de **Task by Task**: creación de grupos individuales (`POST /api/v1/groups`), consulta paginada mediante cursor Keyset (`GET /api/v1/groups`), consulta de detalle (`GET /api/v1/groups/{groupId}`), renombrado de grupos incluyendo el grupo por defecto `"Personal"` (`PATCH /api/v1/groups/{groupId}`), y eliminación de grupos individuales con cancelación transaccional de avisos programados y protección estricta de inmutabilidad sobre el grupo `"Personal"` (`DELETE /api/v1/groups/{groupId}`).

El diseño técnico se fundamenta en:
1. **Reutilización limpia de entidades fundacionales (`001-auth-and-users`):** Se reutilizan las entidades JPA `Group` y `GroupMembership` ya validadas contra `V1__init_schema.sql` (`ddl-auto=validate`), sin añadir migraciones SQL adicionales.
2. **Extensibilidad atómica (`RNF-20`):** Toda creación de grupo individual inserta dentro de la misma transacción (`@Transactional`) el registro en `groups` (`is_personal = false`) y la membresía en `group_memberships` (`role = 'CREATOR'`).
3. **Paginación Keyset $O(1)$ respaldada por índice compuesto (`RNF-04`, `FND-REQ-25`):** Las consultas de `GroupRepository` aprovechan el índice `idx_groups_keyset_order (creator_id, is_personal DESC, created_at ASC, id ASC)` definido en `V1__init_schema.sql`, solicitando `limit + 1` registros para determinar `hasNext` sin ejecutar consultas `COUNT(*)`.
4. **Precedencia estricta de existencia (`404`), autorización (`403`) y estado (`409`):** En todas las operaciones por `{groupId}`, el servicio verifica primero la existencia del grupo (`404 Not Found` vía `ResourceNotFoundException`), luego la titularidad `creator_id == currentUserId` (`403 Forbidden` vía `AccessDeniedException`), y únicamente después evalúa invariantes de negocio como `is_personal == true` en el borrado (`409 Conflict` vía `ResourceConflictException`).
5. **Concurrencia segura y limpieza de `scheduled_tasks` (`RF-07`, `RNF-17`, `FND-REQ-06`):** En `DELETE /api/v1/groups/{groupId}` se adquiere un bloqueo pesimista de escritura (`LockModeType.PESSIMISTIC_WRITE` -> `SELECT ... FOR UPDATE`) sobre la fila del grupo antes de purgar los trabajos `task-due-{taskId}` de `scheduled_tasks` vía `JdbcTemplate` y disparar el `ON DELETE CASCADE` relacional.
6. **Pure Java 21 e inyección de reloj (`DateTimeProvider`):** Cero Lombok/MapStruct, DTOs `record` con normalización `trim()` en su constructor compacto, mapper manual puro (`GroupMapper`), y marcas de tiempo obtenidas de `DateTimeProvider.now()`.

---

## 2. Inventario de Archivos Afectados

```
JavaSpringBoot-TaskByTask/
├── src/main/java/com/eyelesscode/task_by_task/
│   ├── shared/
│   │   └── pagination/
│   │       ├── KeysetCursor.java                                         # [MODIFICAR] Validar isPersonal != null y createdAt != null en GroupCursor
│   │       └── KeysetCursorCodec.java                                    # [MODIFICAR] Validar campos no nulos al decodificar cursor tipo GROUP
│   └── group/
│       ├── Group.java                                                    # [EXISTENTE] Entidad JPA mapeada a tabla `groups`
│       ├── GroupMembership.java                                          # [EXISTENTE] Entidad JPA mapeada a tabla `group_memberships`
│       ├── GroupMembershipRepository.java                                # [EXISTENTE] Repositorio JPA de membresías
│       ├── GroupRepository.java                                          # [MODIFICAR] Añadir consultas Keyset y findByIdForUpdate (PESSIMISTIC_WRITE)
│       ├── GroupMapper.java                                              # [CREAR] Función pura Group -> GroupResponse
│       ├── GroupService.java                                             # [CREAR] Lógica transaccional de grupos, paginación y limpieza de jobs
│       ├── GroupController.java                                          # [CREAR] Endpoints REST bajo /api/v1/groups
│       └── dto/
│           ├── GroupResponse.java                                        # [CREAR] record: id, name, isPersonal, creatorId, createdAt, updatedAt
│           ├── CreateGroupRequest.java                                   # [CREAR] record: name (con trim() y @NotBlank / @Size(3..30))
│           └── UpdateGroupRequest.java                                   # [CREAR] record: name (con trim() y @NotBlank / @Size(3..30))
└── src/test/java/com/eyelesscode/task_by_task/
    ├── shared/
    │   └── pagination/
    │       └── KeysetCursorCodecTest.java                                # [MODIFICAR] Añadir pruebas de rechazo para GroupCursor con campos nulos
    └── group/
        ├── GroupMapperTest.java                                          # [CREAR] Pruebas unitarias de GroupMapper y normalización trim() en DTOs
        ├── GroupCreationAndDetailIntegrationTest.java                    # [CREAR] IT: POST /api/v1/groups y GET /api/v1/groups/{groupId}
        ├── GroupPaginationIntegrationTest.java                           # [CREAR] IT: GET /api/v1/groups (Keyset, orden Personal primero, cursores, límites)
        └── GroupRenameAndDeleteIntegrationTest.java                      # [CREAR] IT: PATCH y DELETE /api/v1/groups/{groupId}, 409 Personal, cascada y jobs
```

---

## 3. Decisiones de Diseño y Alternativas Descartadas

| Decisión de Diseño | Opción Elegida | Alternativa Descartada | Justificación Técnica |
| :--- | :--- | :--- | :--- |
| **Predicado Keyset para orden mixto (`is_personal DESC, created_at ASC, id ASC`)** | Consulta JPQL explícita con condición disyuntiva según `cursor.isPersonal()` | Paginación `OFFSET / LIMIT` o filtrado en memoria | En PostgreSQL `TRUE > FALSE`, por lo que `is_personal DESC` ubica primero `TRUE` y luego `FALSE`. Expresar el predicado seek directamente en JPQL permite usar el índice `idx_groups_keyset_order` con costo $O(1)$ (`RNF-04`). |
| **Detección de `hasNext` en paginación Keyset** | Consultar `PageRequest.of(0, limit + 1)` y pasar los `limit + 1` elementos a `KeysetPage.of(items, limit, nextCursor)` | Ejecutar una segunda consulta `COUNT(*)` | Evita una segunda ida a la base de datos por petición y se integra directamente con el método de fábrica `KeysetPage.of(...)` ya existente en `shared.pagination`. |
| **Precedencia de errores en operaciones por `{groupId}`** | Cargar por `groupId` (`404` si no existe) -> validar `creator.id == userId` (`403` si difiere) -> validar reglas de estado (`409` si `isPersonal` en `DELETE`) | Buscar por `findByIdAndCreatorId` (retornando `404` para grupos ajenos) o validar `isPersonal` antes de permisos | Cumple estrictamente `FND-REQ-18` (`404`) y `FND-REQ-19` (`403`), evitando además filtrar a un tercero si un grupo ajeno es `"Personal"` (`GRP-REQ-12/13/16/17/20/21`, `EC-05`). |
| **Sincronización en borrado de grupo (`DELETE`)** | `@Lock(LockModeType.PESSIMISTIC_WRITE)` (`SELECT ... FOR UPDATE`) sobre `Group` antes de limpiar `scheduled_tasks` y borrar el grupo | Borrado sin bloqueo de fila | Como `scheduled_tasks` (`db-scheduler`) no tiene clave foránea hacia `tasks`, bloquear la fila del grupo serializa la eliminación frente a operaciones concurrentes y garantiza cero jobs huérfanos (`GRP-REQ-19`, `EC-12`). |
| **Normalización de `name` en DTOs de entrada** | Constructor compacto en `CreateGroupRequest` y `UpdateGroupRequest` aplicando `name = name != null ? name.trim() : null` antes de Jakarta Validation | Normalizar manualmente después del controlador | Al normalizar en el constructor compacto del `record`, `@NotBlank` y `@Size(min = 3, max = 30)` evalúan directamente la longitud recortada (`TRIM(name)`) y producen automáticamente el contrato RFC 7807 con `invalidParams` en `GlobalExceptionHandler` (`EC-01`). |

---

## 4. Diseño Detallado de Componentes

### 4.1 Validación Estricta de `KeysetCursor.GroupCursor` (`shared.pagination`)
En `KeysetCursor.java`, el constructor compacto de `GroupCursor` validará que ninguno de los 3 componentes de la tupla de ordenación sea nulo:
```java
record GroupCursor(Boolean isPersonal, Instant createdAt, Long id) implements KeysetCursor {

    public GroupCursor {
        if (id == null) {
            throw new IllegalArgumentException("El id del cursor no puede ser nulo");
        }
        if (isPersonal == null) {
            throw new IllegalArgumentException("isPersonal no puede ser nulo");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt no puede ser nulo");
        }
    }
}
```
En `KeysetCursorCodec.decode(...)`, el `switch (payload.type())` para `"GROUP"` delega en `new KeysetCursor.GroupCursor(payload.isPersonal(), payload.createdAt(), payload.id())` dentro del bloque `try { ... } catch (IllegalArgumentException ex)` que ya traduce cualquier componente nulo en `BadRequestException("El cursor de paginación proporcionado no es válido")` (`GRP-REQ-09`, `EC-08`).

### 4.2 Repositorio `GroupRepository` (`group`)
Se amplía `GroupRepository` con tres métodos adicionales:
```java
public interface GroupRepository extends JpaRepository<Group, Long> {

    Optional<Group> findByCreatorIdAndIsPersonalTrue(Long creatorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM Group g WHERE g.id = :id")
    Optional<Group> findByIdForUpdate(@Param("id") Long id);

    @Query(
            """
            SELECT g FROM Group g
            WHERE g.creator.id = :creatorId
            ORDER BY g.isPersonal DESC, g.createdAt ASC, g.id ASC
            """)
    List<Group> findFirstPageByCreatorId(@Param("creatorId") Long creatorId, Pageable pageable);

    @Query(
            """
            SELECT g FROM Group g
            WHERE g.creator.id = :creatorId
              AND (
                   (:cursorIsPersonal = true AND (
                        g.isPersonal = false
                        OR (g.isPersonal = true AND g.createdAt > :cursorCreatedAt)
                        OR (g.isPersonal = true AND g.createdAt = :cursorCreatedAt AND g.id > :cursorId)
                   ))
                   OR
                   (:cursorIsPersonal = false AND (
                        (g.isPersonal = false AND g.createdAt > :cursorCreatedAt)
                        OR (g.isPersonal = false AND g.createdAt = :cursorCreatedAt AND g.id > :cursorId)
                   ))
              )
            ORDER BY g.isPersonal DESC, g.createdAt ASC, g.id ASC
            """)
    List<Group> findNextPageByCreatorId(
            @Param("creatorId") Long creatorId,
            @Param("cursorIsPersonal") boolean cursorIsPersonal,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);
}
```

### 4.3 DTOs Inmutables y Mapper Manual (`group.dto` y `GroupMapper`)
- **`GroupResponse.java`:**
  ```java
  package com.eyelesscode.task_by_task.group.dto;

  import java.time.Instant;

  public record GroupResponse(
          Long id,
          String name,
          boolean isPersonal,
          Long creatorId,
          Instant createdAt,
          Instant updatedAt) {}
  ```
- **`CreateGroupRequest.java` y `UpdateGroupRequest.java`:**
  ```java
  package com.eyelesscode.task_by_task.group.dto;

  import jakarta.validation.constraints.NotBlank;
  import jakarta.validation.constraints.Size;

  public record CreateGroupRequest(
          @NotBlank(message = "El nombre del grupo es obligatorio")
                  @Size(
                          min = 3,
                          max = 30,
                          message = "El nombre del grupo debe tener entre 3 y 30 caracteres")
                  String name) {

      public CreateGroupRequest {
          if (name != null) {
              name = name.trim();
          }
      }
  }
  ```
  *(Estructura idéntica para `UpdateGroupRequest`).*
- **`GroupMapper.java`:**
  Función pura sin estado:
  ```java
  package com.eyelesscode.task_by_task.group;

  import com.eyelesscode.task_by_task.group.dto.GroupResponse;
  import java.util.Objects;

  public final class GroupMapper {

      private GroupMapper() {}

      public static GroupResponse toResponse(Group group) {
          Objects.requireNonNull(group, "group must not be null");
          return new GroupResponse(
                  group.getId(),
                  group.getName(),
                  group.isPersonal(),
                  group.getCreator().getId(),
                  group.getCreatedAt(),
                  group.getUpdatedAt());
      }
  }
  ```

---

## 5. Flujos de Negocio y Transaccionalidad (`GroupService` y `GroupController`)

### 5.1 Creación de Grupo Individual (`POST /api/v1/groups`)
1. Autenticación y CSRF verificados por `SecurityFilterChain` (`401` si no hay sesión, `403` si falta CSRF).
2. Validación de `@Valid @RequestBody CreateGroupRequest`: si `name` es nulo, en blanco o tras `trim()` no tiene entre 3 y 30 caracteres -> `400 Bad Request` con `invalidParams` (`GRP-REQ-04`, `GRP-REQ-05`, `EC-01`).
3. En `GroupService.createGroup(CreateGroupRequest)` (`@Transactional`):
   - Obtener `userId = SecurityUtils.getCurrentUserId()`.
   - Cargar referencia del usuario en `userRepository.findById(userId)` (`ResourceNotFoundException("Usuario no encontrado")` si no existiera).
   - Obtener `Instant now = dateTimeProvider.now()`.
   - Persistir `new Group(user, request.name(), false, now, now)` en `groupRepository`.
   - Persistir `new GroupMembership(savedGroup, user, "CREATOR", now)` en `groupMembershipRepository` (`GRP-REQ-02`, `RNF-20`).
   - Retornar `GroupMapper.toResponse(savedGroup)` con código `HTTP 201 Created` (`GRP-REQ-01`).

### 5.2 Listado Paginado Keyset de Grupos (`GET /api/v1/groups`)
1. Parámetros de consulta en `GroupController`:
   - `@RequestParam(required = false) String cursor`
   - `@RequestParam(defaultValue = "20") int limit`
2. En `GroupService.listGroups(String cursor, int limit)` (`@Transactional(readOnly = true)`):
   - Validar rango de `limit`: si `limit < 1 || limit > 50`, lanzar `BadRequestException("El límite de paginación debe estar entre 1 y 50")` (`GRP-REQ-10`).
   - Obtener `userId = SecurityUtils.getCurrentUserId()`.
   - Crear `Pageable pageable = PageRequest.of(0, limit + 1)`.
   - Si `cursor == null`:
     - Invocar `groupRepository.findFirstPageByCreatorId(userId, pageable)`.
   - Si `cursor != null`:
     - Decodificar con `KeysetCursor.GroupCursor decoded = keysetCursorCodec.decodeGroupCursor(cursor)` (si es blanco, corrupto, de otro tipo o tiene algún campo nulo, arroja `BadRequestException("El cursor de paginación proporcionado no es válido")`, `GRP-REQ-09`, `EC-08`).
     - Invocar `groupRepository.findNextPageByCreatorId(userId, decoded.isPersonal(), decoded.createdAt(), decoded.id(), pageable)`.
   - Mapear los elementos a `List<GroupResponse>`.
   - Si `responses.size() > limit`, obtener el elemento en el índice `limit - 1` (último de la página actual) y codificar `nextCursor = keysetCursorCodec.encodeGroupCursor(last.isPersonal(), last.createdAt(), last.id())`; en caso contrario `nextCursor = null`.
   - Construir y retornar `KeysetPage.of(responses, limit, nextCursor)` con `HTTP 200 OK` (`GRP-REQ-06`, `GRP-REQ-07`, `GRP-REQ-08`, `EC-07`, `EC-09`).

### 5.3 Consulta de Detalle de Grupo (`GET /api/v1/groups/{groupId}`)
1. En `GroupService.getGroup(Long groupId)` (`@Transactional(readOnly = true)`):
   - Obtener `userId = SecurityUtils.getCurrentUserId()`.
   - Buscar grupo: `Group group = groupRepository.findById(groupId).orElseThrow(() -> new ResourceNotFoundException("Grupo no encontrado"))` (`GRP-REQ-12`).
   - Verificar titularidad: si `!group.getCreator().getId().equals(userId)`, lanzar `new AccessDeniedException("No tiene permisos para realizar esta acción")` (`GRP-REQ-13`, `EC-05`).
   - Retornar `GroupMapper.toResponse(group)` con `HTTP 200 OK` (`GRP-REQ-11`).

### 5.4 Renombrado de Grupo (`PATCH /api/v1/groups/{groupId}`)
1. Validación de `@Valid @RequestBody UpdateGroupRequest` (`400 Bad Request` con `invalidParams` si `name` tras `trim()` no tiene entre 3 y 30 caracteres, `GRP-REQ-15`).
2. En `GroupService.renameGroup(Long groupId, UpdateGroupRequest request)` (`@Transactional`):
   - Obtener `userId = SecurityUtils.getCurrentUserId()`.
   - Buscar grupo por `groupId` (`404 Not Found` si no existe, `GRP-REQ-16`).
   - Verificar titularidad (`403 Forbidden` si `creator.id != userId`, `GRP-REQ-17`).
   - Actualizar `group.setName(request.name())` y `group.setUpdatedAt(dateTimeProvider.now())` (conservando `group.isPersonal()` inmutable, `GRP-REQ-14`, `EC-02`, `EC-04`, `EC-11`).
   - Guardar y retornar `GroupMapper.toResponse(group)` con `HTTP 200 OK`.

### 5.5 Eliminación de Grupo Individual (`DELETE /api/v1/groups/{groupId}`)
1. En `GroupService.deleteGroup(Long groupId)` (`@Transactional`):
   - Obtener `userId = SecurityUtils.getCurrentUserId()`.
   - Adquirir bloqueo pesimista de escritura: `Group group = groupRepository.findByIdForUpdate(groupId).orElseThrow(() -> new ResourceNotFoundException("Grupo no encontrado"))` (`GRP-REQ-19`, `GRP-REQ-20`, `EC-12`).
   - Verificar titularidad **antes** de verificar `isPersonal`: si `!group.getCreator().getId().equals(userId)`, lanzar `new AccessDeniedException("No tiene permisos para realizar esta acción")` (`GRP-REQ-21`, `EC-05`).
   - Verificar protección del grupo `"Personal"`: si `group.isPersonal()`, lanzar `new ResourceConflictException("El grupo Personal no puede ser eliminado ni convertido en compartido")` (`GRP-REQ-18`, `EC-02`).
   - Cancelar trabajos pendientes en `scheduled_tasks` para las tareas del grupo:
     ```java
     jdbcTemplate.update(
             "DELETE FROM scheduled_tasks WHERE task_instance IN ("
                     + "SELECT 'task-due-' || t.id FROM tasks t WHERE t.group_id = ?)",
             groupId);
     ```
   - Eliminar el grupo: `groupRepository.delete(group)` y `groupRepository.flush()` (ejecutando `ON DELETE CASCADE` sobre `tasks`, `tags`, `task_tags` y `group_memberships`, `GRP-REQ-19`, `EC-06`).
2. Retornar `HTTP 204 No Content`.

---

## 6. Estrategia de Pruebas Automatizadas

1. **Pruebas Unitarias Puras (sin contenedor):**
   - **`KeysetCursorCodecTest`:** Verificar que `GroupCursor` rechace `isPersonal == null`, `createdAt == null` o `id == null` tanto al construir el record como al decodificar un payload JSON manipulado con checksum válido (`GRP-REQ-09`, `EC-08`).
   - **`GroupMapperTest`:** Verificar el mapeo exacto de `Group` a `GroupResponse` y la normalización `trim()` en los constructores compactos de `CreateGroupRequest` y `UpdateGroupRequest` (`EC-01`).
2. **Pruebas de Integración con `@SpringBootTest` + `Testcontainers` (`postgres:alpine`) y `ddl-auto=validate`:**
   - **`GroupCreationAndDetailIntegrationTest`:**
     - `POST /api/v1/groups`: creación exitosa `201 Created`, `isPersonal = false`, recorte `trim()`, creación atómica de `GroupMembership` con `role = 'CREATOR'`, creación de múltiples grupos con nombres iguales (`EC-03`), e ignorar `"isPersonal": true` enviado en el JSON (`EC-11`).
     - Rechazos en `POST`: `400` con `invalidParams` por nombre vacío, `< 3` tras `trim()` o `> 30` chars (`GRP-REQ-05`, `EC-01`), `400` por JSON malformado (`GRP-REQ-25`, `EC-10`), `401` sin sesión (`GRP-REQ-23`), `403` sin token CSRF (`GRP-REQ-24`).
     - `GET /api/v1/groups/{groupId}`: `200 OK` para grupo `"Personal"` e individual propios (`GRP-REQ-11`), `404` para ID inexistente (`GRP-REQ-12`), `403` para grupo de otro usuario (`GRP-REQ-13`, `EC-05`), `400` para `{groupId}` no numérico (`GRP-REQ-25`, `EC-10`).
   - **`GroupPaginationIntegrationTest`:**
     - `GET /api/v1/groups`: el grupo `"Personal"` aparece siempre primero (`GRP-REQ-07`) incluso tras ser renombrado (`EC-02`).
     - Recorrido completo multi-página con `limit` pequeño verificando `content`, `nextCursor`, `hasNext`, `size` y orden estricto `(is_personal DESC, created_at ASC, id ASC)` incluyendo desempate por `id ASC` cuando `created_at` es idéntico (`GRP-REQ-06`, `GRP-REQ-08`, `EC-07`).
     - Aislamiento entre usuarios y página vacía (`content: []`, `hasNext: false`, `nextCursor: null`, `size: 0`) cuando el cursor apunta a un grupo eliminado, al final de la lista o proviene de otro usuario (`EC-09`).
     - Rechazo `400` ante `limit < 1` o `limit > 50` (`GRP-REQ-10`), `limit` no entero (`GRP-REQ-25`), y cursores corruptos o de tipo `TASK`/`TAG` (`GRP-REQ-09`, `EC-08`).
   - **`GroupRenameAndDeleteIntegrationTest`:**
     - `PATCH /api/v1/groups/{groupId}`: renombrado exitoso de grupo individual y del grupo `"Personal"` preservando `isPersonal = true`, actualizando `updatedAt`, idempotencia al renombrar con el mismo nombre (`EC-04`), ignorar `"isPersonal"` en el body (`EC-11`), y creación posterior de otro grupo llamado `"Personal"` (`EC-02`).
     - Rechazos en `PATCH`: `400` por nombre inválido (`GRP-REQ-15`), `404` por grupo inexistente (`GRP-REQ-16`), `403` por grupo ajeno (`GRP-REQ-17`), `403` sin CSRF (`GRP-REQ-24`).
     - `DELETE /api/v1/groups/{groupId}`: rechazo `409 Conflict` al intentar eliminar el propio grupo `"Personal"` (`GRP-REQ-18`), rechazo `403 Forbidden` al intentar eliminar el grupo `"Personal"` o individual de otro usuario verificando precedencia `403` antes de `409` (`GRP-REQ-21`, `EC-05`), rechazo `404` si no existe (`GRP-REQ-20`), y eliminación `204 No Content` de grupo individual verificando cascada en `tasks`, `tags`, `task_tags`, `group_memberships` y borrado de jobs `task-due-{taskId}` en `scheduled_tasks` preservando intactos los jobs de otros grupos (`GRP-REQ-19`, `EC-06`, `EC-12`).

---

## 7. Matriz de Cobertura de Requerimientos

| Requisito EARS / Caso Borde | Componente / Archivo de Implementación | Clase de Prueba de Verificación |
| :--- | :--- | :--- |
| **GRP-REQ-01** a **05** | `GroupService.createGroup()`, `CreateGroupRequest`, `GroupController` | `GroupCreationAndDetailIntegrationTest`, `GroupMapperTest` |
| **GRP-REQ-06** a **10** | `GroupService.listGroups()`, `GroupRepository`, `KeysetCursor`, `KeysetCursorCodec` | `GroupPaginationIntegrationTest`, `KeysetCursorCodecTest` |
| **GRP-REQ-11** a **13** | `GroupService.getGroup()`, `GroupController` | `GroupCreationAndDetailIntegrationTest` |
| **GRP-REQ-14** a **17** | `GroupService.renameGroup()`, `UpdateGroupRequest`, `GroupController` | `GroupRenameAndDeleteIntegrationTest` |
| **GRP-REQ-18** a **21** | `GroupService.deleteGroup()`, `GroupRepository.findByIdForUpdate()` | `GroupRenameAndDeleteIntegrationTest` |
| **GRP-REQ-22** a **25** | `GlobalExceptionHandler`, `SecurityConfig`, `RestAccessDeniedHandler` | `GroupCreationAndDetailIntegrationTest`, `GroupPaginationIntegrationTest`, `GroupRenameAndDeleteIntegrationTest` |
| **EC-01**, **EC-11** | `CreateGroupRequest`, `UpdateGroupRequest` | `GroupMapperTest`, `GroupCreationAndDetailIntegrationTest`, `GroupRenameAndDeleteIntegrationTest` |
| **EC-02**, **EC-03**, **EC-04** | `GroupService.createGroup()`, `GroupService.renameGroup()`, `GroupService.deleteGroup()` | `GroupCreationAndDetailIntegrationTest`, `GroupPaginationIntegrationTest`, `GroupRenameAndDeleteIntegrationTest` |
| **EC-05** | `GroupService` (`getGroup`, `renameGroup`, `deleteGroup`) | `GroupCreationAndDetailIntegrationTest`, `GroupRenameAndDeleteIntegrationTest` |
| **EC-06**, **EC-12** | `GroupService.deleteGroup()`, `GroupRepository.findByIdForUpdate()` | `GroupRenameAndDeleteIntegrationTest` |
| **EC-07**, **EC-08**, **EC-09** | `KeysetCursor.GroupCursor`, `KeysetCursorCodec`, `GroupRepository` | `KeysetCursorCodecTest`, `GroupPaginationIntegrationTest` |
| **EC-10** | `GlobalExceptionHandler`, `GroupController` | `GroupCreationAndDetailIntegrationTest`, `GroupPaginationIntegrationTest` |
