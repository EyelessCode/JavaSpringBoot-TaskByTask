# Lista de Tareas: 003-tasks-and-tags (Gestión de Tareas, Etiquetas por Grupo y Filtros Básicos)

**Fase:** 003-tasks-and-tags  
**Estado:** pending  
**Fecha:** 2026-11-15  
**Autor:** SDD Planner Agent  
**Referencias:**
- `docs/specs/003-tasks-and-tags/spec.md` (TSK-REQ-01 a TSK-REQ-27, TAG-REQ-01 a TAG-REQ-09, EC-01 a EC-11)
- `docs/specs/003-tasks-and-tags/plan.md` (Diseño técnico y arquitectura)
- `docs/constitution.md` (Principios 1 a 6)
- `AGENTS.md` (Comandos de verificación y domain rules)
- `src/main/resources/db/migration/V1__init_schema.sql` (Esquema DDL de referencia)

---

## Tareas de Implementación

- [x] **T1: Fundaciones compartidas, validación estricta de `TaskCursor`, escape SQL LIKE y consulta multi-grupo (`shared/`, `group/`)**
  - **Requisitos cubiertos:** TSK-REQ-17, TSK-REQ-20, TSK-REQ-22, TSK-REQ-27, TAG-REQ-03, EC-08, EC-09, Constitución Principios 3 y 4.
  - **Archivos a crear/modificar:**
    - `src/main/java/com/eyelesscode/task_by_task/shared/pagination/KeysetCursor.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/pagination/KeysetCursorCodec.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/pagination/SqlLikePattern.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/error/FieldValidationException.java`
    - `src/main/java/com/eyelesscode/task_by_task/shared/error/GlobalExceptionHandler.java`
    - `src/main/java/com/eyelesscode/task_by_task/group/GroupRepository.java`
    - `src/test/java/com/eyelesscode/task_by_task/shared/pagination/KeysetCursorCodecTest.java`
    - `src/test/java/com/eyelesscode/task_by_task/shared/pagination/SqlLikePatternTest.java`
    - `src/test/java/com/eyelesscode/task_by_task/shared/error/GlobalExceptionHandlerTest.java`
  - **Descripción:**
    - Actualizar `KeysetCursor.TaskCursor` y `KeysetCursorCodec.decode(...)` (`case "TASK"`) para exigir `completed != null` y `createdAt != null` además de `id != null` y `dueTime -> dueDate` (lanzando `IllegalArgumentException` -> `BadRequestException("El cursor de paginación proporcionado no es válido")`).
    - Crear `SqlLikePattern` con `ESCAPE_CHAR = '!'`, `escape(String)`, `containsIgnoreCase(String)` y `startsWithIgnoreCase(String)`.
    - Crear `FieldValidationException` (portando `List<ValidationErrorDetail>`) y extraer un helper compartido en `GlobalExceptionHandler` para emitir el mismo contrato RFC 7807 `Error de validación` en `MethodArgumentNotValidException`, `ConstraintViolationException` y `FieldValidationException`.
    - Añadir `findAllByIdInWithCreator(Collection<Long> ids)` con `JOIN FETCH g.creator` en `GroupRepository`.
    - Escribir/ampliar pruebas unitarias en `KeysetCursorCodecTest`, `SqlLikePatternTest` y `GlobalExceptionHandlerTest`.
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test -Dtest=KeysetCursorCodecTest,SqlLikePatternTest,GlobalExceptionHandlerTest` pasan al 100%.

- [x] **T2: Módulo `tag`: entidad `Tag`, reglas compartidas, autocompletado Keyset, creación idempotente/concurrente y eliminación en grupo**
  - **Requisitos cubiertos:** TAG-REQ-01, TAG-REQ-02, TAG-REQ-03, TAG-REQ-04, TAG-REQ-05, TAG-REQ-06, TAG-REQ-08, TAG-REQ-09, TSK-REQ-21, TSK-REQ-22 a 27, EC-05, EC-06, EC-08, EC-09, EC-10.
  - **Archivos a crear:**
    - `src/main/java/com/eyelesscode/task_by_task/tag/Tag.java`
    - `src/main/java/com/eyelesscode/task_by_task/tag/TagNameRules.java`
    - `src/main/java/com/eyelesscode/task_by_task/tag/TagRepository.java`
    - `src/main/java/com/eyelesscode/task_by_task/tag/TagMapper.java`
    - `src/main/java/com/eyelesscode/task_by_task/tag/dto/CreateTagRequest.java`
    - `src/main/java/com/eyelesscode/task_by_task/tag/dto/TagResponse.java`
    - `src/main/java/com/eyelesscode/task_by_task/tag/TagService.java`
    - `src/main/java/com/eyelesscode/task_by_task/tag/TagController.java`
    - `src/test/java/com/eyelesscode/task_by_task/tag/TagNameRulesTest.java`
    - `src/test/java/com/eyelesscode/task_by_task/tag/TagMapperTest.java`
    - `src/test/java/com/eyelesscode/task_by_task/tag/TagEndpointsIntegrationTest.java`
  - **Descripción:**
    - Mapear `Tag` contra tabla `tags` (`ddl-auto=validate`) y crear `TagNameRules` (constantes + validación + deduplicación case-insensitive preservando primer caso) y `CreateTagRequest`/`TagResponse`/`TagMapper`.
    - Implementar en `TagRepository` las consultas `findByIdWithGroupAndCreator`, `findByGroupIdAndNormalizedName` (`nativeQuery` sobre `TRIM(LOWER(name))`), y consultas Keyset ordenadas `t.name ASC, t.id ASC` con/sin prefijo `LOWER(t.name) LIKE :prefix ESCAPE '!'`.
    - Implementar en `TagService`: `listGroupTags` (`limit 1..50`, `TagCursor`), `createOrReuseTag` y `resolveOrCreateTag` / `resolveOrCreateTags` usando `INSERT INTO tags (group_id, name, created_at) VALUES (?, ?, ?) ON CONFLICT (group_id, TRIM(LOWER(name))) DO NOTHING RETURNING id` + `SELECT` ganador (truncando `now` a `ChronoUnit.MICROS`), y `deleteGroupTag` verificando `groupId` (`404` -> `403`) antes de `tagId` (`404` -> `403` -> mismatch de grupo `404` `"Etiqueta no encontrada"`).
    - Exponer `GET`, `POST` (`201 Created` si nueva / `200 OK` si existente) y `DELETE` (`204 No Content`) en `TagController` (`/api/v1/groups/{groupId}/tags`).
    - Crear pruebas unitarias (`TagNameRulesTest`, `TagMapperTest`) e integración (`TagEndpointsIntegrationTest`) verificando `GET`/`POST`/`DELETE`, comodines SQL (`\`, `%`, `_`), cascada sobre `task_tags` y carrera concurrente con hilos paralelos (`CountDownLatch`).
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test -Dtest=TagNameRulesTest,TagMapperTest,TagEndpointsIntegrationTest` pasan al 100%.

- [x] **T3: Entidades `Task` y `TaskTag`, repositorios base, frontera `scheduled_tasks` y `TaskMapper`**
  - **Requisitos cubiertos:** TSK-REQ-01, TSK-REQ-05, TSK-REQ-06, TSK-REQ-11, RNF-20, FND-REQ-29, FND-REQ-30, FND-REQ-31, EC-02.
  - **Archivos a crear:**
    - `src/main/java/com/eyelesscode/task_by_task/task/Task.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/TaskTagId.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/TaskTag.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/TaskStatusFilter.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/TaskRepository.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/TaskTagRepository.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/TaskDueSchedulePort.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/JdbcTaskDueScheduleAdapter.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/dto/TaskResponse.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/TaskMapper.java`
    - `src/test/java/com/eyelesscode/task_by_task/task/TaskMapperTest.java`
  - **Descripción:**
    - Crear entidades JPA `Task` (`tasks` con `assignedUser` nullable para `RNF-20`), `TaskTagId` y `TaskTag` (`task_tags` mapeando `assigned_at`).
    - Crear `TaskRepository` con `findByIdWithGroupAndCreator` y `findByIdForUpdate` (`@Lock(LockModeType.PESSIMISTIC_WRITE)` con `JOIN FETCH t.group` o carga conjunta) y `TaskTagRepository` con carga batch `findByTaskIdsOrderByTagNameAndId(Collection<Long> taskIds)` ordenada por `tt.tag.name ASC, tt.tag.id ASC`.
    - Crear `TaskDueSchedulePort` y `JdbcTaskDueScheduleAdapter` para `INSERT ... ON CONFLICT (task_name, task_instance) DO UPDATE ...` y `DELETE` sobre `("task-due-notification", "task-due-{taskId}")`.
    - Crear `TaskResponse` y `TaskMapper.toResponse(...)` asegurando `!task.isCompleted() && dateTimeProvider.isOverdue(...)`.
    - Crear `TaskMapperTest` verificando mapeo completo, `assignedUserId = null`, orden de tags y que `completed = true` siempre resulte en `overdue = false` incluso con fecha pasada.
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test -Dtest=TaskMapperTest,ApplicationIntegrationTest` pasan al 100% (validando `ddl-auto=validate`).

- [x] **T4: Creación y consulta de detalle de tareas (`POST /api/v1/groups/{groupId}/tasks` y `GET /api/v1/tasks/{taskId}`)**
  - **Requisitos cubiertos:** TSK-REQ-01, TSK-REQ-02, TSK-REQ-03, TSK-REQ-04, TSK-REQ-05, TSK-REQ-06, TSK-REQ-11, TSK-REQ-22 a 27, TAG-REQ-01, TAG-REQ-02, EC-01, EC-05, EC-11.
  - **Archivos a crear:**
    - `src/main/java/com/eyelesscode/task_by_task/task/dto/CreateTaskRequest.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/TaskService.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/TaskController.java`
    - `src/test/java/com/eyelesscode/task_by_task/task/TaskCreationAndDetailIntegrationTest.java`
  - **Descripción:**
    - Crear `CreateTaskRequest` con constructor compacto normalizando `title`, `description` (blanco -> `null`) y `tags` (preservando elementos `null` para `@NotBlank` en `tags[i]`) y reutilizando constantes de `TagNameRules`.
    - Implementar `TaskService.createTask(Long groupId, CreateTaskRequest)` y `TaskService.getTask(Long taskId)` con sincronización en `scheduled_tasks` (`DateTimeProvider.calculateDueTriggerInstant`), cálculo de `overdue` según `ZoneId` del usuario, e inyección segura contra propiedades server-managed (`EC-11`).
    - Exponer `POST /api/v1/groups/{groupId}/tasks` (`201 Created`) y `GET /api/v1/tasks/{taskId}` (`200 OK`) en `TaskController`.
    - Crear `TaskCreationAndDetailIntegrationTest` cubriendo creación con/sin fechas/etiquetas (`tags: null` -> `[]`, deduplicación `EC-05`), `EC-01` (fechas pasadas, hoy antes/después de 09:00 AM en zona horaria local y límite `23:59:59.999` mediante `Clock` determinista), rechazo `dueTime` sin `dueDate`, errores `400`/`401`/`403`/`404` y `EC-11`.
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test -Dtest=TaskCreationAndDetailIntegrationTest` pasan al 100%.

- [x] **T5: Edición parcial explícita de tareas (`PATCH /api/v1/tasks/{taskId}`) con `PatchField` y Jackson 3**
  - **Requisitos cubiertos:** TSK-REQ-02, TSK-REQ-03, TSK-REQ-04, TSK-REQ-05, TSK-REQ-07, TSK-REQ-11, TSK-REQ-22 a 27, TAG-REQ-01, TAG-REQ-02, TAG-REQ-09, EC-02, EC-03, EC-04, EC-05, EC-10, EC-11.
  - **Archivos a crear/modificar:**
    - `src/main/java/com/eyelesscode/task_by_task/task/dto/PatchField.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/dto/UpdateTaskRequestDeserializer.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/dto/UpdateTaskRequest.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/dto/TaskPatchValidator.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/TaskService.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/TaskController.java`
    - `src/test/java/com/eyelesscode/task_by_task/task/UpdateTaskRequestDeserializerTest.java`
    - `src/test/java/com/eyelesscode/task_by_task/task/TaskUpdateIntegrationTest.java`
  - **Descripción:**
    - Implementar `PatchField<T>`, `UpdateTaskRequestDeserializer` (`tools.jackson.databind.ValueDeserializer<UpdateTaskRequest>`), `UpdateTaskRequest` y `TaskPatchValidator` (reutilizando `TagNameRules` y arrojando `FieldValidationException` con `invalidParams` exactos para `"title"`, `"description"`, `"tags[i]"`).
    - Implementar `TaskService.updateTask(Long taskId, UpdateTaskRequest)`: validar entrada, adquirir `findByIdForUpdate`, validar pertenencia (`404` -> `403`), tratar `{}` sin efectos secundarios, aplicar reglas `EC-03` de `dueDate`/`dueTime`, reemplazar enlaces `task_tags` sin eliminar etiquetas del catálogo del grupo (`EC-04`), actualizar `updatedAt` si hubo cambios, y sincronizar `scheduled_tasks` solo si `!task.isCompleted()` (nunca programar si `completed = true`, `EC-02`).
    - Escribir pruebas unitarias (`UpdateTaskRequestDeserializerTest`) e integración (`TaskUpdateIntegrationTest`) cubriendo `{}` inerte, `EC-02`, `EC-03`, `EC-04`, `EC-05`, `EC-11` y actualizaciones concurrentes (`EC-10`).
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test -Dtest=UpdateTaskRequestDeserializerTest,TaskUpdateIntegrationTest` pasan al 100%.

- [x] **T6: Completado, eliminación de tareas y desasociación selectiva de etiquetas (`PATCH /completion`, `DELETE /tasks/{taskId}`, `DELETE /tasks/{taskId}/tags/{tagId}`)**
  - **Requisitos cubiertos:** TSK-REQ-08, TSK-REQ-09, TSK-REQ-10, TSK-REQ-11, TAG-REQ-07, TAG-REQ-09, TSK-REQ-22 a 27, EC-02, EC-06, EC-10.
  - **Archivos a crear/modificar:**
    - `src/main/java/com/eyelesscode/task_by_task/task/dto/UpdateTaskCompletionRequest.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/TaskService.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/TaskController.java`
    - `src/test/java/com/eyelesscode/task_by_task/task/TaskCompletionAndDeletionIntegrationTest.java`
  - **Descripción:**
    - Implementar `UpdateTaskCompletionRequest` (`@NotNull Boolean completed`) y `TaskService.updateCompletion`: bloqueo `findByIdForUpdate`, transición `false <-> true` (idempotente si no cambia, `completedAt = now` o `null`, cancelación/reprogramación de `scheduled_tasks`), y cálculo de `overdue`.
    - Implementar `TaskService.deleteTask`: `findByIdForUpdate`, cancelación en `scheduled_tasks`, borrado de tarea en cascada sobre `task_tags` preservando `tags`.
    - Implementar `TaskService.detachTagFromTask`: precedencia estricta `taskId` (`404` -> `403`) -> `tagId` (`404` -> `403`) -> enlace en `task_tags` (`404` `"La etiqueta no está asociada a esta tarea"`), preservando `Tag` en `tags` (`TAG-REQ-09`).
    - Exponer los tres endpoints en `TaskController` y crear `TaskCompletionAndDeletionIntegrationTest` cubriendo todos los contratos `200`/`204`/`400`/`401`/`403`/`404`, `EC-02`, `EC-06` y limpieza selectiva de `scheduled_tasks`.
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test -Dtest=TaskCompletionAndDeletionIntegrationTest` pasan al 100%.

- [x] **T7: Mover y copiar tareas entre grupos (`POST /api/v1/tasks/{taskId}/move` y `POST /api/v1/tasks/{taskId}/copy`)**
  - **Requisitos cubiertos:** TSK-REQ-11, TSK-REQ-12, TSK-REQ-13, TSK-REQ-14, TSK-REQ-22 a 27, TAG-REQ-02, TAG-REQ-09, EC-07.
  - **Archivos a crear/modificar:**
    - `src/main/java/com/eyelesscode/task_by_task/task/dto/TaskTargetGroupRequest.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/TaskService.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/TaskController.java`
    - `src/test/java/com/eyelesscode/task_by_task/task/TaskMoveAndCopyIntegrationTest.java`
  - **Descripción:**
    - Crear `TaskTargetGroupRequest` (`@NotNull Long targetGroupId`).
    - Implementar `TaskService.moveTask` y `TaskService.copyTask` respetando la precedencia exacta `400 targetGroupId` -> `{taskId}` (`404`/`403`) -> `targetGroupId` (`404`/`403`) (`TSK-REQ-14`).
    - En `moveTask`: idempotente si `targetGroupId == sourceGroupId`; en grupo distinto conserva `id`, `createdAt`, `completed`, `completedAt`, `dueDate`, `dueTime` y `scheduled_tasks`, resuelve/crea etiquetas en destino reemplazando `task_tags` y deja intactas las etiquetas del origen (`EC-07`).
    - En `copyTask`: crea nueva tarea (`201 Created`) con `createdAt = now`, duplica propiedades/estado/etiquetas en destino (incluyendo mismo grupo) y sincroniza `("task-due-notification", "task-due-{newTaskId}")` si aplica.
    - Exponer ambos endpoints en `TaskController` y crear `TaskMoveAndCopyIntegrationTest`.
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test -Dtest=TaskMoveAndCopyIntegrationTest` pasan al 100%.

- [x] **T8: Listados Keyset con orden compuesto y filtros combinados (`GET /api/v1/tasks` y `GET /api/v1/groups/{groupId}/tasks`)**
  - **Requisitos cubiertos:** TSK-REQ-15, TSK-REQ-16, TSK-REQ-17, TSK-REQ-18, TSK-REQ-19, TSK-REQ-20, TSK-REQ-21, TSK-REQ-22 a 27, EC-08, EC-09, RNF-04.
  - **Archivos a crear/modificar:**
    - `src/main/java/com/eyelesscode/task_by_task/task/TaskQueryRepository.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/TaskQueryRepositoryImpl.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/TaskRepository.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/TaskService.java`
    - `src/main/java/com/eyelesscode/task_by_task/task/TaskController.java`
    - `src/test/java/com/eyelesscode/task_by_task/task/TaskPaginationAndFilterIntegrationTest.java`
  - **Descripción:**
    - Implementar `TaskQueryRepository` y `TaskQueryRepositoryImpl` usando `EntityManager` con JPQL dinámico (`ORDER BY t.completed ASC, t.dueDate ASC NULLS LAST, t.dueTime ASC NULLS LAST, t.createdAt ASC, t.id ASC`, `setMaxResults(limit + 1)`) cubriendo las 3 ramas exactas de `TaskCursor` (`dueDate/dueTime`, solo `dueDate`, sin fecha) y transición `false -> true` en `ALL`.
    - Implementar `TaskService.listTasks` y `TaskService.listGroupTasks` validando `limit` (`1..50`), compatibilidad `status <-> cursor.completed`, deduplicación y precedencia `404` -> `403` de `groupIds` (`?groupIds=` vacío = todos los grupos del usuario), escape `SqlLikePattern.containsIgnoreCase` solo sobre `title`, y carga batch de etiquetas ordenadas `name ASC, id ASC`.
    - Exponer `GET /api/v1/tasks` y `GET /api/v1/groups/{groupId}/tasks` en `TaskController`.
    - Crear `TaskPaginationAndFilterIntegrationTest` probando recorrido multi-página con `limit = 1` y `limit = 2` a través de las 3 variantes temporales + `ALL`, empates `(created_at, id)`, filtros combinados, `search` con `%`, `_` y `\`, `groupIds` duplicados/vacíos/`404`-antes-`403`, cursores incompatibles/corruptos y lista completa de tags en respuestas de listado.
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test -Dtest=TaskPaginationAndFilterIntegrationTest` pasan al 100%.

- [ ] **T9: Verificación integral de calidad, formateo Spotless, suite completa de pruebas y actualización de `MEMORY.md`**
  - **Requisitos cubiertos:** Todos los requisitos del módulo (`TSK-REQ-01` a `TSK-REQ-27`, `TAG-REQ-01` a `TAG-REQ-09`, `EC-01` a `EC-11`), Constitución Principios 3 y 6.
  - **Archivos a modificar/verificar:**
    - `MEMORY.md`
    - Todo el repositorio.
  - **Descripción:** Ejecutar `./mvnw spotless:apply` seguido de `./mvnw spotless:check compile test-compile`. Ejecutar `./mvnw test` verificando que el 100% de las pruebas unitarias y de integración con Testcontainers pasen sin omisiones ni fallos. Actualizar `MEMORY.md` registrando la finalización de `003-tasks-and-tags`, decisiones clave (Jackson 3 `PatchField`, `ON CONFLICT DO NOTHING` en etiquetas, `TaskDueSchedulePort` y JPQL Keyset `NULLS LAST`) y el nuevo conteo total de pruebas.
  - **Criterio de finalización (Done when):** `./mvnw spotless:check compile test-compile` y `./mvnw test` finalizan con éxito (`BUILD SUCCESS`) y `MEMORY.md` está actualizado.
