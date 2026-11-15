# Lista de Tareas: 002-groups (Gestión de Grupos Individuales y Grupo "Personal")

**Fase:** 002-groups  
**Estado:** ready  
**Fecha:** 2026-11-15  
**Autor:** SDD Planner Agent  
**Referencias:**
- `docs/specs/002-groups/spec.md` (GRP-REQ-01 a GRP-REQ-25, EC-01 a EC-12)
- `docs/specs/002-groups/plan.md` (Diseño técnico y arquitectura)
- `docs/constitution.md` (Principios 1 a 6)
- `AGENTS.md` (Comandos de verificación y domain rules)
- `src/main/resources/db/migration/V1__init_schema.sql` (Esquema DDL de referencia)

---

## Tareas de Implementación

- [x] **T1: Validación estricta de `GroupCursor`, consultas Keyset/bloqueo pesimista en `GroupRepository`, DTOs y `GroupMapper`**
  - **Requisitos cubiertos:** GRP-REQ-01, GRP-REQ-04, GRP-REQ-06, GRP-REQ-09, GRP-REQ-19, EC-01, EC-08, EC-11, EC-12, Constitución Principio 3 y 6.
  - **Archivos a crear/modificar:**
    - `src/main/java/com/eyelesscode/task_by_task/shared/pagination/KeysetCursor.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/pagination/KeysetCursorCodec.java`
    - `src/main/java/com/eyelesscode/task_by_task/group/GroupRepository.java`
    - `src/main/java/com/eyelesscode/task_by_task/group/dto/GroupResponse.java`
    - `src/main/java/com/eyelesscode/task_by_task/group/dto/CreateGroupRequest.java`
    - `src/main/java/com/eyelesscode/task_by_task/group/dto/UpdateGroupRequest.java`
    - `src/main/java/com/eyelesscode/task_by_task/group/GroupMapper.java`
    - `src/test/java/com/eyelesscode/task_by_task/shared/pagination/KeysetCursorCodecTest.java`
    - `src/test/java/com/eyelesscode/task_by_task/group/GroupMapperTest.java`
  - **Descripción:**
    - Actualizar `KeysetCursor.GroupCursor` y `KeysetCursorCodec` para exigir que `isPersonal`, `createdAt` e `id` no sean nulos (lanzando `IllegalArgumentException` -> `BadRequestException("El cursor de paginación proporcionado no es válido")`).
    - Ampliar `GroupRepository` con `findFirstPageByCreatorId`, `findNextPageByCreatorId` (predicado Keyset sobre `isPersonal DESC, createdAt ASC, id ASC`) y `findByIdForUpdate` con `@Lock(LockModeType.PESSIMISTIC_WRITE)`.
    - Crear los records `GroupResponse`, `CreateGroupRequest` y `UpdateGroupRequest` (con normalización `trim()` en el constructor compacto y anotaciones `@NotBlank` y `@Size(min = 3, max = 30)` en español, sin exponer `isPersonal`) y la clase pura `GroupMapper`.
    - Ampliar `KeysetCursorCodecTest` y crear `GroupMapperTest` verificando constructores compactos, mapeo y rechazo de cursores de grupo incompletos.
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test -Dtest=KeysetCursorCodecTest,GroupMapperTest,ApplicationIntegrationTest` pasan al 100%.

- [x] **T2: Creación de grupos individuales y consulta de detalle por ID (`POST /api/v1/groups` y `GET /api/v1/groups/{groupId}`)**
  - **Requisitos cubiertos:** GRP-REQ-01, GRP-REQ-02, GRP-REQ-03, GRP-REQ-04, GRP-REQ-05, GRP-REQ-11, GRP-REQ-12, GRP-REQ-13, GRP-REQ-22, GRP-REQ-23, GRP-REQ-24, GRP-REQ-25, EC-01, EC-03, EC-05, EC-10, EC-11, RNF-20.
  - **Archivos a crear:**
    - `src/main/java/com/eyelesscode/task_by_task/group/GroupService.java`
    - `src/main/java/com/eyelesscode/task_by_task/group/GroupController.java`
    - `src/test/java/com/eyelesscode/task_by_task/group/GroupCreationAndDetailIntegrationTest.java`
  - **Descripción:**
    - Implementar `GroupService.createGroup(CreateGroupRequest)` de forma transaccional: crear `Group` con `isPersonal = false`, `creator = currentUser`, `createdAt = updatedAt = dateTimeProvider.now()`, e insertar atómicamente `GroupMembership` con `role = "CREATOR"`.
    - Implementar `GroupService.getGroup(Long groupId)`: buscar por ID (`404 Not Found` `"Grupo no encontrado"` si no existe), verificar `group.getCreator().getId().equals(currentUserId)` (`403 Forbidden` vía `AccessDeniedException` si pertenece a otro usuario), y retornar `GroupResponse`.
    - Exponer `POST /api/v1/groups` (`201 Created`) y `GET /api/v1/groups/{groupId}` (`200 OK`) en `GroupController`.
    - Crear `GroupCreationAndDetailIntegrationTest` sobre Testcontainers PostgreSQL cubriendo creación exitosa, membresía `CREATOR`, nombres duplicados permitidos, recorte `trim()`, ignorar `"isPersonal": true` en el JSON, errores `400` (`invalidParams` y JSON/path malformados), `401` sin sesión, `403` sin CSRF o grupo de otro usuario, y `404` cuando no existe.
  - **Criterio de finalización (Done when):** `./mvnw spotless:check` y `./mvnw test -Dtest=GroupCreationAndDetailIntegrationTest` pasan al 100%.

- [ ] **T3: Listado paginado Keyset de grupos (`GET /api/v1/groups`)**
  - **Requisitos cubiertos:** GRP-REQ-06, GRP-REQ-07, GRP-REQ-08, GRP-REQ-09, GRP-REQ-10, GRP-REQ-22, GRP-REQ-23, GRP-REQ-25, EC-02, EC-03, EC-07, EC-08, EC-09, EC-10, RNF-04.
  - **Archivos a crear/modificar:**
    - `src/main/java/com/eyelesscode/task_by_task/group/GroupService.java`
    - `src/main/java/com/eyelesscode/task_by_task/group/GroupController.java`
    - `src/test/java/com/eyelesscode/task_by_task/group/GroupPaginationIntegrationTest.java`
  - **Descripción:**
    - Implementar `GroupService.listGroups(String cursor, int limit)` validando que `1 <= limit <= 50` (lanzando `BadRequestException("El límite de paginación debe estar entre 1 y 50")` si está fuera de rango), decodificando el cursor opcional con `keysetCursorCodec.decodeGroupCursor(cursor)`, consultando `limit + 1` filas en `GroupRepository`, codificando `nextCursor` cuando `items.size() > limit`, y retornando `KeysetPage.of(...)`.
    - Exponer `GET /api/v1/groups` en `GroupController` con `@RequestParam(required = false) String cursor` y `@RequestParam(defaultValue = "20") int limit`.
    - Crear `GroupPaginationIntegrationTest` verificando que el grupo `"Personal"` aparezca siempre primero, paginación progresiva multi-página sin duplicados ni omisiones, desempate por `id ASC` ante igual `created_at`, página vacía (`content: []`, `hasNext: false`, `nextCursor: null`, `size: 0`) cuando el cursor apunta tras el último elemento o a una tupla eliminada/ajena, y rechazos `400` ante `limit` fuera de `1..50`, `limit` no numérico o cursores corruptos/de otro tipo.
  - **Criterio de finalización (Done when):** `./mvnw spotless:check` y `./mvnw test -Dtest=GroupPaginationIntegrationTest` pasan al 100%.

- [ ] **T4: Renombrado y eliminación de grupos con protección del grupo `"Personal"` y limpieza de `scheduled_tasks` (`PATCH` y `DELETE /api/v1/groups/{groupId}`)**
  - **Requisitos cubiertos:** GRP-REQ-14, GRP-REQ-15, GRP-REQ-16, GRP-REQ-17, GRP-REQ-18, GRP-REQ-19, GRP-REQ-20, GRP-REQ-21, GRP-REQ-22, GRP-REQ-23, GRP-REQ-24, GRP-REQ-25, EC-01, EC-02, EC-04, EC-05, EC-06, EC-10, EC-11, EC-12, RF-06, RF-07, RNF-17.
  - **Archivos a crear/modificar:**
    - `src/main/java/com/eyelesscode/task_by_task/group/GroupService.java`
    - `src/main/java/com/eyelesscode/task_by_task/group/GroupController.java`
    - `src/test/java/com/eyelesscode/task_by_task/group/GroupRenameAndDeleteIntegrationTest.java`
  - **Descripción:**
    - Implementar `GroupService.renameGroup(Long groupId, UpdateGroupRequest)` verificando existencia (`404`) y titularidad (`403`), actualizando `name` y `updatedAt = dateTimeProvider.now()` tanto para grupos individuales como para `"Personal"` (manteniendo `isPersonal` inalterado).
    - Implementar `GroupService.deleteGroup(Long groupId)` adquiriendo bloqueo pesimista con `groupRepository.findByIdForUpdate(groupId)` (`404` si no existe), verificando titularidad (`403` **antes** de verificar `isPersonal`), rechazando con `ResourceConflictException("El grupo Personal no puede ser eliminado ni convertido en compartido")` (`409 Conflict`) si `group.isPersonal()`, eliminando transaccionalmente los jobs pendientes en `scheduled_tasks` (`DELETE FROM scheduled_tasks WHERE task_instance IN (SELECT 'task-due-' || t.id FROM tasks t WHERE t.group_id = ?)`) vía `JdbcTemplate`, y eliminando el grupo en cascada (`204 No Content`).
    - Exponer `PATCH /api/v1/groups/{groupId}` y `DELETE /api/v1/groups/{groupId}` en `GroupController`.
    - Crear `GroupRenameAndDeleteIntegrationTest` verificando renombrado de grupo individual y `"Personal"`, idempotencia con el mismo nombre, creación posterior de otro grupo llamado `"Personal"`, rechazo `409` al intentar borrar el propio `"Personal"`, precedencia `403` al intentar borrar el `"Personal"` de otro usuario, cascada completa (`tasks`, `tags`, `task_tags`, `group_memberships`) y limpieza selectiva de `scheduled_tasks`.
  - **Criterio de finalización (Done when):** `./mvnw spotless:check` y `./mvnw test -Dtest=GroupRenameAndDeleteIntegrationTest` pasan al 100%.

- [ ] **T5: Verificación de calidad integral, formateo Spotless, suite completa de pruebas y actualización de `MEMORY.md`**
  - **Requisitos cubiertos:** Todos los requisitos del módulo (`GRP-REQ-01` a `GRP-REQ-25`, `EC-01` a `EC-12`), Constitución Principios 3 y 6.
  - **Archivos a modificar/verificar:**
    - `MEMORY.md`
    - Todo el repositorio.
  - **Descripción:** Ejecutar `./mvnw spotless:apply` seguido de `./mvnw spotless:check compile test-compile`. Ejecutar `./mvnw test` verificando que el 100% de las pruebas unitarias y de integración con Testcontainers pasen sin errores. Actualizar `MEMORY.md` registrando la finalización del módulo `002-groups`.
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test` finalizan con éxito (`BUILD SUCCESS`) y `MEMORY.md` está actualizado.
