# Plan Técnico de Implementación: 003-tasks-and-tags (Gestión de Tareas, Etiquetas por Grupo y Filtros Básicos)

**Fase:** 003-tasks-and-tags  
**Estado:** approved (basado en `spec.md` aprobada)  
**Fecha:** 2026-11-15  
**Autor:** SDD Planner Agent  
**Referencias:**
- `docs/specs/003-tasks-and-tags/spec.md` (TSK-REQ-01 a TSK-REQ-27, TAG-REQ-01 a TAG-REQ-09, EC-01 a EC-11)
- `docs/specs/000-foundation/spec.md` y `plan.md` (Esquema V1, `KeysetCursorCodec`, `DateTimeProvider`, `GlobalExceptionHandler`, `SecurityConfig`)
- `docs/specs/002-groups/spec.md` y `plan.md` (Convenciones de arquitectura vertical, `404` -> `403`, bloqueo pesimista y limpieza de `scheduled_tasks`)
- `docs/constitution.md` (Principios 1 a 6)
- `AGENTS.md` (Convenciones Pure Java 21, domain rules y comandos de verificación)
- `src/main/resources/db/migration/V1__init_schema.sql` (`tasks`, `tags`, `task_tags`, `scheduled_tasks`, índices Keyset y restricciones)

---

## 1. Resumen Ejecutivo y Enfoque de Diseño

El objetivo del módulo **003-tasks-and-tags** es implementar el ciclo de vida completo de tareas, etiquetas con alcance por grupo y listados paginados filtrables del MVP de **Task by Task**, respetando estrictamente `ddl-auto=validate` sobre `V1__init_schema.sql` sin migraciones SQL adicionales y preservando la extensibilidad `RNF-20` (`assigned_user_id` nullable).

El diseño técnico se sustenta en seis pilares:

1. **Dependencia acíclica entre paquetes verticales (`shared <- user <- group <- tag <- task`):**
   El paquete `tag` depende únicamente de `group` y `shared` (gestionando `/api/v1/groups/{groupId}/tags` y exponiendo `resolveOrCreateTag` / `resolveOrCreateTags` para `task`), mientras que el paquete `task` depende unidireccionalmente de `tag`, `group`, `user` y `shared` (gestionando `/api/v1/groups/{groupId}/tasks`, `/api/v1/tasks/**` y `/api/v1/tasks/{taskId}/tags/{tagId}`). Cero dependencias circulares.
2. **Mapeo JPA exacto con `TaskTag` explícito y cero N+1 (`ddl-auto=validate`):**
   Se mapean `Task` (`tasks`), `Tag` (`tags`) y la tabla de unión `TaskTag` (`task_tags` con `@EmbeddedId TaskTagId` y `assigned_at`). En listados, las tareas se cargan mediante `JOIN FETCH t.group` con `setMaxResults(limit + 1)` y sus etiquetas se recuperan en **una única consulta adicional `IN :taskIds`** ordenada por `name ASC, id ASC`, evitando productos cartesianos en paginación y consultas N+1.
3. **Semántica explícita `null`-vs-omitido en `PATCH` con Jackson 3 (`tools.jackson.databind`):**
   Spring Boot 4.1.2 utiliza Jackson 3 (`tools.jackson.databind`) en la capa MVC. `UpdateTaskRequest` (Java `record`) encapsula sus cinco atributos actualizables en `PatchField<T>` (`omitted()` vs `of(value)`) mediante `UpdateTaskRequestDeserializer extends ValueDeserializer<UpdateTaskRequest>`, ignorando campos desconocidos/controlados por el servidor (`EC-11`) y validando campos presentes vía `TaskPatchValidator` + `FieldValidationException` con formato idéntico RFC 7807 `invalidParams`.
4. **Paginación Keyset $O(1)$ sin `COUNT(*)` y predicado exacto `NULLS LAST`:**
   `TaskQueryRepositoryImpl` construye JPQL dinámico tipado con `EntityManager` (`setMaxResults(limit + 1)`) incluyendo únicamente los filtros activos (`status`, `search`, `groupIds`) y la rama exacta del predicado seek para la tupla `(completed ASC, due_date ASC NULLS LAST, due_time ASC NULLS LAST, created_at ASC, id ASC)` según los 3 estados posibles de `(cursor.dueDate(), cursor.dueTime())`, evitando parámetros nulos sin tipo en PostgreSQL y aprovechando `idx_tasks_keyset_order` e `idx_tags_keyset_order`.
5. **Concurrencia segura (`RNF-17`, `EC-10`):**
   - Las mutaciones unitarias sobre una tarea (`PATCH`, `PATCH /completion`, `move`, `DELETE`) adquieren bloqueo pesimista `LockModeType.PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE`) sobre la fila de `tasks`, serializando el orden de actualización/borrado y la sincronización de `scheduled_tasks`.
   - La creación concurrente de etiquetas en un mismo grupo utiliza `INSERT INTO tags ... ON CONFLICT (group_id, TRIM(LOWER(name))) DO NOTHING RETURNING id` seguido de `SELECT` por `TRIM(LOWER(name))`, resolviendo carreras sobre `uq_tags_group_normalized_name` sin abortar la transacción PostgreSQL ni contaminar la sesión Hibernate.
6. **Frontera desacoplada con `004-notifications` (`TSK-REQ-11`) y paridad con `002-groups`:**
   `TaskDueSchedulePort` / `JdbcTaskDueScheduleAdapter` gestionan transaccionalmente la clave primaria compuesta `("task-due-notification", "task-due-{taskId}")` en `scheduled_tasks` usando `DateTimeProvider.calculateDueTriggerInstant(...)` y el mismo formato de fila (`task_name = 'task-due-notification'`, `task_instance = 'task-due-' || taskId`, `picked = false`, `version = 1`) empleado por `GroupService.deleteGroup` y verificado en `GroupRenameAndDeleteIntegrationTest`.

---

## 2. Inventario de Archivos Afectados

```
JavaSpringBoot-TaskByTask/
├── src/main/java/com/eyelesscode/task_by_task/
│   ├── shared/
│   │   ├── error/
│   │   │   ├── FieldValidationException.java                          # [CREAR] Excepción programática con List<ValidationErrorDetail>
│   │   │   └── GlobalExceptionHandler.java                            # [MODIFICAR] Soportar FieldValidationException con helper compartido
│   │   └── pagination/
│   │       ├── KeysetCursor.java                                      # [MODIFICAR] Exigir completed != null y createdAt != null en TaskCursor
│   │       ├── KeysetCursorCodec.java                                 # [MODIFICAR] Rechazar TASK con completed == null o createdAt == null
│   │       └── SqlLikePattern.java                                    # [CREAR] Utilidad pura para escapar !, %, _ con ESCAPE '!'
│   ├── group/
│   │   └── GroupRepository.java                                       # [MODIFICAR] Añadir findAllByIdInWithCreator(Collection<Long>)
│   ├── tag/
│   │   ├── Tag.java                                                   # [CREAR] Entidad JPA tabla `tags`
│   │   ├── TagRepository.java                                         # [CREAR] Consultas por grupo, TRIM(LOWER(name)) y Keyset prefix
│   │   ├── TagNameRules.java                                          # [CREAR] Constantes y reglas compartidas (trim, 1..30, sin `,`/`;`)
│   │   ├── TagMapper.java                                             # [CREAR] Función pura Tag -> TagResponse
│   │   ├── TagService.java                                            # [CREAR] Listado Keyset, POST idempotente/concurrente, DELETE grupo y upsert interno
│   │   ├── TagController.java                                         # [CREAR] Endpoints /api/v1/groups/{groupId}/tags
│   │   └── dto/
│   │       ├── CreateTagRequest.java                                  # [CREAR] record(name) con trim() + Jakarta Validation
│   │       └── TagResponse.java                                       # [CREAR] record(id, groupId, name, createdAt)
│   └── task/
│       ├── Task.java                                                  # [CREAR] Entidad JPA tabla `tasks` (`assignedUser` nullable RNF-20)
│       ├── TaskTagId.java                                             # [CREAR] @Embeddable PK compuesta (taskId, tagId)
│       ├── TaskTag.java                                               # [CREAR] Entidad JPA tabla `task_tags` con `assignedAt`
│       ├── TaskStatusFilter.java                                      # [CREAR] Enum PENDING, COMPLETED, ALL
│       ├── TaskRepository.java                                        # [CREAR] CRUD, findByIdForUpdate (PESSIMISTIC_WRITE), findByIdWithGroupAndCreator
│       ├── TaskQueryRepository.java                                   # [CREAR] Contrato fragmento personalizado para listado Keyset
│       ├── TaskQueryRepositoryImpl.java                               # [CREAR] JPQL dinámico con EntityManager (NULLS LAST, limit + 1)
│       ├── TaskTagRepository.java                                     # [CREAR] Batch fetch ordenado (name ASC, id ASC) y borrado por task
│       ├── TaskDueSchedulePort.java                                   # [CREAR] Puerto desacoplado de scheduled_tasks
│       ├── JdbcTaskDueScheduleAdapter.java                            # [CREAR] UPSERT / DELETE JDBC sobre PK (task_name, task_instance)
│       ├── TaskMapper.java                                            # [CREAR] Función pura Task + tags + userZone + now -> TaskResponse
│       ├── TaskService.java                                           # [CREAR] CRUD, completion, move, copy, filtros y desasociación task-tag
│       ├── TaskController.java                                        # [CREAR] Endpoints /api/v1/tasks/**, /groups/{groupId}/tasks, /tasks/{taskId}/tags/{tagId}
│       └── dto/
│           ├── PatchField.java                                        # [CREAR] Contenedor tipado Omitted vs Present(value)
│           ├── UpdateTaskRequestDeserializer.java                     # [CREAR] ValueDeserializer<UpdateTaskRequest> (Jackson 3)
│           ├── TaskPatchValidator.java                                # [CREAR] Validación compartida de UpdateTaskRequest -> FieldValidationException
│           ├── CreateTaskRequest.java                                 # [CREAR] record con normalización compacta + Bean Validation
│           ├── UpdateTaskRequest.java                                 # [CREAR] record con @JsonDeserialize(using = UpdateTaskRequestDeserializer.class)
│           ├── UpdateTaskCompletionRequest.java                       # [CREAR] record(@NotNull Boolean completed)
│           ├── TaskTargetGroupRequest.java                            # [CREAR] record(@NotNull Long targetGroupId)
│           └── TaskResponse.java                                      # [CREAR] record completo uniforme (incluye assignedUserId y overdue)
└── src/test/java/com/eyelesscode/task_by_task/
    ├── shared/
    │   ├── error/
    │   │   └── GlobalExceptionHandlerTest.java                        # [MODIFICAR] Verificar FieldValidationException -> RFC 7807 invalidParams
    │   └── pagination/
    │       ├── KeysetCursorCodecTest.java                             # [MODIFICAR] Pruebas TaskCursor con completed/createdAt nulos
    │       └── SqlLikePatternTest.java                                # [CREAR] Pruebas unitarias de escape de !, %, _ y \
    ├── tag/
    │   ├── TagNameRulesTest.java                                      # [CREAR] Pruebas unitarias de validación y normalización de etiquetas
    │   ├── TagMapperTest.java                                         # [CREAR] Pruebas unitarias de TagMapper y CreateTagRequest
    │   └── TagEndpointsIntegrationTest.java                           # [CREAR] IT: GET/POST/DELETE /groups/{groupId}/tags y carrera concurrente EC-10
    └── task/
        ├── TaskMapperTest.java                                        # [CREAR] Pruebas unitarias de TaskMapper, overdue con completed=true y DTOs
        ├── UpdateTaskRequestDeserializerTest.java                     # [CREAR] Pruebas unitarias Jackson 3 Omitted vs null vs tipos inválidos + Validator
        ├── TaskCreationAndDetailIntegrationTest.java                  # [CREAR] IT: POST/GET tareas, EC-01 (09:00 AM / 23:59:59.999), EC-05, EC-11
        ├── TaskUpdateIntegrationTest.java                             # [CREAR] IT: PATCH parcial, {}, EC-02, EC-03, EC-04, EC-05, concurrencia EC-10
        ├── TaskCompletionAndDeletionIntegrationTest.java              # [CREAR] IT: PATCH /completion, DELETE tarea, DELETE task-tag (EC-02, EC-06, EC-10)
        ├── TaskMoveAndCopyIntegrationTest.java                        # [CREAR] IT: POST /move y /copy, precedencia TSK-REQ-14, EC-07
        └── TaskPaginationAndFilterIntegrationTest.java                # [CREAR] IT: GET /tasks y /groups/{groupId}/tasks, filtros, 3 ramas cursor, EC-08, EC-09
```

---

## 3. Decisiones de Diseño y Alternativas Descartadas

| Decisión de Diseño | Opción Elegida | Alternativa Descartada | Justificación Técnica |
| :--- | :--- | :--- | :--- |
| **Distinción `null` vs omitido en `PATCH /tasks/{taskId}`** | `PatchField<T>` + `UpdateTaskRequestDeserializer extends ValueDeserializer<UpdateTaskRequest>` (`tools.jackson.databind`) | `Optional<T>` o `Map<String, Object>` sin tipo | Spring Boot 4.1.2 usa Jackson 3 (`tools.jackson.databind`). `ValueDeserializer` lee el `JsonNode` objeto, distingue exactamente ausencia (`!root.has(field)`) de `"field": null`, ignora propiedades desconocidas (`EC-11`) y rechaza tipos JSON inválidos (`reportInputMismatch` -> `400 Solicitud inválida`) manteniendo un `record` inmutable `UpdateTaskRequest`. |
| **Validación uniforme de `UpdateTaskRequest`** | `TaskPatchValidator` + `FieldValidationException` (reutilizando `TagNameRules` y builder RFC 7807 en `GlobalExceptionHandler`) | `@Constraint` de clase con `Errors.rejectValue("tags[0]", ...)` sobre `PatchField` | `SpringValidatorAdapter` usa `BeanWrapperImpl` sobre las propiedades reales del DTO y falla con `NotReadablePropertyException` cuando el campo es `PatchField<List<String>>` en vez de `List<String>`. `FieldValidationException` garantiza nombres exactos (`"title"`, `"description"`, `"tags[i]"`) y reutiliza la misma salida RFC 7807 con `invalidParams`. |
| **Relación `tasks <-> tags` (`task_tags`)** | Entidad explícita `TaskTag` (`@EmbeddedId TaskTagId`) mapeando `assigned_at` | `@ManyToMany` sin entidad intermedia | Permite poblar `assigned_at` con `DateTimeProvider.now()`, evita `MultipleBagFetchException`, permite carga batch en 1 consulta (`IN :taskIds ORDER BY tag.name ASC, tag.id ASC`) y desasociación directa por clave compuesta sin cargar colecciones innecesarias. |
| **Predicado Keyset de tareas y `NULLS LAST`** | JPQL dinámico en `TaskQueryRepositoryImpl` (`EntityManager` + `setMaxResults(limit + 1)`) con 3 ramas según `(dueDate, dueTime)` | Consulta estática con `:param IS NULL` o `PageRequest` (`COUNT(*)`) | Evita `COUNT(*)`, evita el error JDBC de PostgreSQL `could not determine data type of parameter` en parámetros nulos y genera predicados exactos que explotan `idx_tasks_keyset_order`. |
| **Escape de comodines SQL (`search` y `query`)** | `SqlLikePattern.escape(String)` usando `ESCAPE '!'` (`!`, `%`, `_`) | `ESCAPE '\'` literal en JPQL | Evita ambigüedades léxicas de backslash entre cadenas Java, parser HQL/JPQL de Hibernate y `standard_conforming_strings` de PostgreSQL; trata `\`, `%`, `_` y `!` como literales exactos (`EC-08`). |
| **Creación concurrente de etiquetas (`EC-10`)** | `SELECT` rápido + `INSERT INTO tags ... ON CONFLICT (group_id, TRIM(LOWER(name))) DO NOTHING RETURNING id` + `SELECT` ganador | Capturar `DataIntegrityViolationException` en la misma transacción JPA | En PostgreSQL una excepción SQL marca la transacción como `ABORTED` e invalida comandos posteriores. `ON CONFLICT DO NOTHING` evita el error SQL, mantiene sana la transacción y garantiza cero `500`/`409` bajo concurrencia. |
| **Precisión temporal `TIMESTAMPTZ` vs `TaskCursor`** | Truncar `Instant` de creación/actualización a `ChronoUnit.MICROS` | Guardar nanosegundos de `Instant.now()` | PostgreSQL `TIMESTAMPTZ` almacena microsegundos (6 decimales). Truncar a `MICROS` asegura igualdad exacta entre el `createdAt` retornado en memoria y el almacenado en PostgreSQL usado en `TaskCursor`. |
| **Frontera con `004-notifications`** | Puerto `TaskDueSchedulePort` + `JdbcTaskDueScheduleAdapter` sobre `scheduled_tasks` | Invocar handlers de `notification/` o usar `SchedulerClient` acoplado a `Task<Void>` | `004-notifications` aún no registra el bean `OneTimeTask` `"task-due-notification"`. Operar vía `JdbcTemplate` sobre la PK `(task_name, task_instance)` dentro de la transacción de negocio garantiza atomicidad y total compatibilidad con `GroupService.deleteGroup`. |

---

## 4. Diseño Detallado de Componentes

### 4.1 Extensiones en `shared`

#### 4.1.1 `KeysetCursor.TaskCursor` y `KeysetCursorCodec` (`shared.pagination`)
En `KeysetCursor.TaskCursor`:
```java
record TaskCursor(
        Boolean completed, LocalDate dueDate, LocalTime dueTime, Instant createdAt, Long id)
        implements KeysetCursor {

    public TaskCursor {
        if (id == null) {
            throw new IllegalArgumentException("El id del cursor no puede ser nulo");
        }
        if (completed == null) {
            throw new IllegalArgumentException("completed no puede ser nulo");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt no puede ser nulo");
        }
        if (dueTime != null && dueDate == null) {
            throw new IllegalArgumentException("dueTime requiere dueDate");
        }
    }
}
```
En `KeysetCursorCodec.decode(...)`, dentro de `case "TASK"`, se valida explícitamente `payload.completed() == null || payload.createdAt() == null || (payload.dueTime() != null && payload.dueDate() == null)` lanzando `BadRequestException("El cursor de paginación proporcionado no es válido")` (`TSK-REQ-20`, `EC-09`).

#### 4.1.2 `FieldValidationException` y `GlobalExceptionHandler` (`shared.error`)
- **`FieldValidationException extends RuntimeException`:** almacena una lista inmutable no vacía de `ValidationErrorDetail`.
- **`GlobalExceptionHandler`:** extrae el helper privado `buildValidationResponse(List<ValidationErrorDetail> rawErrors, HttpServletRequest request)` que ordena por `ValidationErrorDetail::name`, construye `ProblemDetail` (`status: 400`, `title: "Error de validación"`, `detail: "Uno o más campos contienen errores de validación"`, `instance`, propiedad `invalidParams`) y lo reutiliza para `MethodArgumentNotValidException`, `ConstraintViolationException` y `FieldValidationException`.

#### 4.1.3 `SqlLikePattern` (`shared.pagination`)
Clase utilitaria final pura:
- `public static final char ESCAPE_CHAR = '!';`
- `public static String escape(String literal)`: reemplaza `"!"` -> `"!!"`, `"%"` -> `"!%"`, `"_"` -> `"!_"`.
- `public static String containsIgnoreCase(String trimmedInput)`: `"%" + escape(trimmedInput.toLowerCase(Locale.ROOT)) + "%"`.
- `public static String startsWithIgnoreCase(String trimmedInput)`: `escape(trimmedInput.toLowerCase(Locale.ROOT)) + "%"`.

### 4.2 Extensión en `group`

En `GroupRepository`:
```java
@Query("SELECT g FROM Group g JOIN FETCH g.creator WHERE g.id IN :ids")
List<Group> findAllByIdInWithCreator(@Param("ids") Collection<Long> ids);
```
Permite cargar en una sola consulta los grupos solicitados por `groupIds` junto con su `creator` bajo `spring.jpa.open-in-view=false`, validando primero existencia (`foundGroups.size() != distinctIds.size()` -> `404` `"Grupo no encontrado"`) y luego titularidad (`403`, `TSK-REQ-17`, `EC-08`).

### 4.3 Módulo `tag`

#### 4.3.1 Entidad `Tag` (`tags`)
Mapeada exactamente a `V1__init_schema.sql`:
- `id`: `@Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long` (`BIGINT`)
- `group`: `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "group_id", nullable = false) Group`
- `name`: `@Column(nullable = false, length = 30) String`
- `createdAt`: `@Column(name = "created_at", nullable = false, updatable = false) Instant`

#### 4.3.2 Reglas Compartidas `TagNameRules`
Constantes públicas reutilizadas tanto en anotaciones Jakarta (`CreateTaskRequest`, `CreateTagRequest`) como en `TaskPatchValidator`:
- `MIN_LENGTH = 1`, `MAX_LENGTH = 30`
- `NO_DELIMITERS_REGEX = "^[^,;]*$"`
- `REQUIRED_MESSAGE = "El nombre de la etiqueta es obligatorio"`
- `LENGTH_MESSAGE = "El nombre de la etiqueta debe tener entre 1 y 30 caracteres"`
- `DELIMITERS_MESSAGE = "El nombre de la etiqueta no puede contener comas ni punto y coma"`
- Métodos puros:
  - `validateTagName(String trimmedOrNullName, String fieldName, List<ValidationErrorDetail> errors)`
  - `validateTagList(List<String> tags, List<ValidationErrorDetail> errors)` (permite lista nula o vacía; valida cada elemento `i` reportando `tags[i]`)
  - `deduplicatePreservingFirstCase(List<String> validTrimmedTags)` (deduplica por `tag.toLowerCase(Locale.ROOT)` en `LinkedHashMap`, `EC-05`).

#### 4.3.3 `TagRepository` y Upsert Concurrente
- `@Query("SELECT t FROM Tag t JOIN FETCH t.group g JOIN FETCH g.creator WHERE t.id = :tagId") Optional<Tag> findByIdWithGroupAndCreator(@Param("tagId") Long tagId);`
- Consulta nativa alineada con el índice funcional `uq_tags_group_normalized_name`:
  ```java
  @Query(
          value =
                  "SELECT * FROM tags WHERE group_id = :groupId "
                          + "AND TRIM(LOWER(name)) = TRIM(LOWER(:name)) LIMIT 1",
          nativeQuery = true)
  Optional<Tag> findByGroupIdAndNormalizedName(
          @Param("groupId") Long groupId, @Param("name") String name);
  ```
- Consultas Keyset para `GET /api/v1/groups/{groupId}/tags` usando JPQL con `ORDER BY t.name ASC, t.id ASC` (alineado con `idx_tags_keyset_order`):
  - Si `query` es nulo/blanco tras `trim()`: consulta sin filtro `LIKE` (evita `:prefix IS NULL`).
  - Si `query` tiene texto: `AND LOWER(t.name) LIKE :prefixPattern ESCAPE '!'`.
  - Si `cursor != null`: `AND (t.name > :cursorName OR (t.name = :cursorName AND t.id > :cursorId))`.
  - Se ejecuta vía `PageRequest.of(0, limit + 1)` (cero `COUNT(*)`).

- **Resolución atómica `resolveOrCreateTag(Group group, String trimmedName, Instant now)` en `TagService`:**
  1. Consultar `tagRepository.findByGroupIdAndNormalizedName(group.getId(), trimmedName)`. Si existe, retornar `TagResolution(existing, false)`.
  2. Ejecutar vía `JdbcTemplate` (misma conexión transaccional JPA):
     ```sql
     INSERT INTO tags (group_id, name, created_at)
     VALUES (?, ?, ?)
     ON CONFLICT (group_id, TRIM(LOWER(name))) DO NOTHING
     RETURNING id
     ```
  3. Si `RETURNING id` devuelve un `id`: cargar `tagRepository.findById(insertedId).orElseThrow()` y retornar `TagResolution(createdTag, true)`.
  4. Si `RETURNING id` devuelve vacío (carrera concurrente resuelta por otro hilo): cargar `tagRepository.findByGroupIdAndNormalizedName(group.getId(), trimmedName).orElseThrow()` y retornar `TagResolution(winner, false)`.

- **Resolución por lotes `resolveOrCreateTags(Group group, List<String> rawTrimmedTags, Instant now)`:**
  Deduplica por `toLowerCase(Locale.ROOT)` (`EC-05`), resuelve o crea cada etiqueta en el grupo destino y retorna la lista de entidades `Tag` únicas.

#### 4.3.4 DTOs y Endpoints de `TagController` (`/api/v1/groups/{groupId}/tags`)
- `TagResponse(Long id, Long groupId, String name, Instant createdAt)`
- `CreateTagRequest(@NotBlank(...) @Size(max = TagNameRules.MAX_LENGTH, ...) @Pattern(...) String name)` con `trim()` en el constructor compacto.
- `GET /api/v1/groups/{groupId}/tags`: valida `limit` (`1..50`), verifica `groupId` (`404` -> `403`), decodifica `TagCursor` si presente, aplica `SqlLikePattern.startsWithIgnoreCase` sobre `query` si no es blanco tras `trim()`, retorna `KeysetPage<TagResponse>`.
- `POST /api/v1/groups/{groupId}/tags`: valida `@Valid CreateTagRequest`, verifica `groupId` (`404` -> `403`), llama `resolveOrCreateTag`, retorna `201 Created` si `created == true` o `200 OK` si `created == false`.
- `DELETE /api/v1/groups/{groupId}/tags/{tagId}`: verifica `groupId` (`404` `"Grupo no encontrado"` -> `403`), busca `tagId` con `findByIdWithGroupAndCreator` (`404` `"Etiqueta no encontrada"` -> `403` si `tag.group.creator.id != userId`), verifica `tag.group.id.equals(groupId)` (si pertenece a otro grupo del mismo usuario -> `404` `"Etiqueta no encontrada"`, `TSK-REQ-25`, `EC-06`), ejecuta `tagRepository.delete(tag); tagRepository.flush();` (`ON DELETE CASCADE` sobre `task_tags`) y retorna `204 No Content`.

### 4.4 Módulo `task`

#### 4.4.1 Entidades `Task`, `TaskTagId`, `TaskTag`
- **`Task` (`tasks`):**
  - `id`: `BIGINT GENERATED ALWAYS AS IDENTITY`
  - `group`: `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "group_id", nullable = false)`
  - `assignedUser`: `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "assigned_user_id")` (`null` siempre en MVP, `RNF-20`)
  - `title`: `@Column(nullable = false, length = 100)`
  - `description`: `@Column(length = 750)` (nullable)
  - `completed`: `@Column(nullable = false)`
  - `completedAt`: `@Column(name = "completed_at")` (nullable)
  - `dueDate`: `@Column(name = "due_date")` (nullable, `LocalDate`)
  - `dueTime`: `@Column(name = "due_time")` (nullable, `LocalTime`)
  - `createdAt`: `@Column(name = "created_at", nullable = false, updatable = false)`
  - `updatedAt`: `@Column(name = "updated_at", nullable = false)`
- **`TaskTagId` (`@Embeddable`):** `Long taskId`, `Long tagId` (`task_id`, `tag_id`), `equals`/`hashCode`.
- **`TaskTag` (`task_tags`):**
  - `@EmbeddedId private TaskTagId id;`
  - `@ManyToOne(fetch = FetchType.LAZY) @MapsId("taskId") @JoinColumn(name = "task_id", nullable = false) private Task task;`
  - `@ManyToOne(fetch = FetchType.LAZY) @MapsId("tagId") @JoinColumn(name = "tag_id", nullable = false) private Tag tag;`
  - `@Column(name = "assigned_at", nullable = false, updatable = false) private Instant assignedAt;`

#### 4.4.2 Puerto de Programación `TaskDueSchedulePort` y `JdbcTaskDueScheduleAdapter`
```java
public interface TaskDueSchedulePort {
    String TASK_NAME = "task-due-notification";
    String INSTANCE_PREFIX = "task-due-";

    void scheduleOrReschedule(Long taskId, Instant executionTime);

    void cancel(Long taskId);
}
```
En `JdbcTaskDueScheduleAdapter` (`@Component` usando `JdbcTemplate`, dentro de la transacción `@Transactional` de `TaskService`):
- `scheduleOrReschedule(taskId, executionTime)`:
  ```sql
  INSERT INTO scheduled_tasks (task_name, task_instance, execution_time, picked, version)
  VALUES ('task-due-notification', ?, ?, false, 1)
  ON CONFLICT (task_name, task_instance)
  DO UPDATE SET
      execution_time = EXCLUDED.execution_time,
      picked = false,
      picked_by = NULL,
      last_failure = NULL,
      consecutive_failures = NULL,
      version = scheduled_tasks.version + 1
  ```
- `cancel(taskId)`:
  ```sql
  DELETE FROM scheduled_tasks
  WHERE task_name = 'task-due-notification' AND task_instance = ?
  ```
En `TaskService`, el método privado `syncDueSchedule(Task task, ZoneId userZone, Instant now)`:
- Si `task.isCompleted()` es `true`: invoca `taskDueSchedulePort.cancel(task.getId())` (nunca programa trabajos sobre tareas completadas, `EC-02`).
- Si `task.isCompleted()` es `false`: calcula `Optional<Instant> trigger = dateTimeProvider.calculateDueTriggerInstant(task.getDueDate(), task.getDueTime(), userZone, now)`. Si está presente, invoca `taskDueSchedulePort.scheduleOrReschedule(task.getId(), trigger.get())`; si es vacío, invoca `taskDueSchedulePort.cancel(task.getId())`.

#### 4.4.3 DTOs, Deserialización Jackson 3 y Validación
- **`CreateTaskRequest`:**
  - `title`: `@NotBlank` + `@Size(min = 2, max = 100)` (trimmed en constructor compacto).
  - `description`: `@Size(max = 750)` (trimmed en constructor compacto; vacío tras `trim()` -> `null`).
  - `dueDate` (`LocalDate`), `dueTime` (`LocalTime`).
  - `tags`: `List<@NotBlank(...) @Size(max = TagNameRules.MAX_LENGTH, ...) @Pattern(...) String>`. En constructor compacto, si `tags != null`, se mapea con `.stream().map(t -> t != null ? t.trim() : null).toList()` (preservando elementos `null` para que `@NotBlank` reporte `tags[i]` en `invalidParams`, `EC-05`).
- **`PatchField<T>` y `UpdateTaskRequest`:**
  - `public record PatchField<T>(boolean present, T value)` con fábricas `omitted()` y `of(T value)`.
  - `UpdateTaskRequest(PatchField<String> title, PatchField<String> description, PatchField<LocalDate> dueDate, PatchField<LocalTime> dueTime, PatchField<List<String>> tags)` anotado con `@tools.jackson.databind.annotation.JsonDeserialize(using = UpdateTaskRequestDeserializer.class)`. Constructor compacto reemplaza cualquier `PatchField` nulo pasado directamente por `PatchField.omitted()`.
  - **`UpdateTaskRequestDeserializer extends tools.jackson.databind.ValueDeserializer<UpdateTaskRequest>`:**
    - Lee `JsonNode root = ctxt.readTree(p);`. Si `root == null || !root.isObject()`, invoca `ctxt.reportInputMismatch(this, "...")` (`400 Solicitud inválida`, `TSK-REQ-27`).
    - Ignora propiedades desconocidas o controladas por el servidor (`EC-11`).
    - Para `"title"`: ausente -> `omitted()`; `isNull()` -> `of(null)`; `isString()` -> `of(node.stringValue().trim())`; otro tipo -> `reportInputMismatch`.
    - Para `"description"`: ausente -> `omitted()`; `isNull()` -> `of(null)`; `isString()` -> normaliza blanco a `null` si `trim().isEmpty()`, si no `of(trimmed)`; otro tipo -> `reportInputMismatch`.
    - Para `"dueDate"` / `"dueTime"`: ausente -> `omitted()`; `isNull()` -> `of(null)`; `isString()` -> `ctxt.readTreeAsValue(node, LocalDate.class / LocalTime.class)`; otro tipo (número/array/objeto/booleano) -> `reportInputMismatch`.
    - Para `"tags"`: ausente -> `omitted()`; `isNull()` -> `of(null)`; `isArray()` -> recorre elementos: `isNull()` agrega `null`, `isString()` agrega `elem.stringValue().trim()`, otro tipo -> `reportInputMismatch`. No-array -> `reportInputMismatch`.
  - **`TaskPatchValidator`:** invocado al inicio de `updateTask` (o desde `TaskController.updateTask`) antes de consultar BD: valida `title` (si presente: no nulo/blanco y longitud `2..100`), `description` (si presente y no nulo: longitud `<= 750`), y `tags` (si presente y no nulo: `TagNameRules.validateTagList(...)`). Si hay errores, lanza `FieldValidationException(errors)`.
- **`UpdateTaskCompletionRequest(@NotNull(message = "El estado de completado es obligatorio") Boolean completed)`**
- **`TaskTargetGroupRequest(@NotNull(message = "El grupo destino es obligatorio") Long targetGroupId)`**
- **`TaskResponse`:** `id, groupId, assignedUserId, title, description, completed, completedAt, overdue, dueDate, dueTime, List<TagResponse> tags, createdAt, updatedAt`.

#### 4.4.4 `TaskMapper` y Regla Crítica de `overdue` (`TSK-REQ-05`)
En `TaskMapper.toResponse(Task task, List<TagResponse> tags, DateTimeProvider dateTimeProvider, ZoneId userZone, Instant now)`:
```java
boolean overdue =
        !task.isCompleted()
                && dateTimeProvider.isOverdue(
                        task.getDueDate(), task.getDueTime(), userZone, now);
```
`DateTimeProvider.isOverdue(...)` no recibe `completed`, por lo que `TaskMapper` fuerza `overdue = false` siempre que `task.isCompleted()` sea `true` (`RF-14`, `EC-02`).

#### 4.4.5 `TaskQueryRepositoryImpl` — Predicado Keyset Exacto (`TSK-REQ-18`, `EC-09`)
Orden compuesto fijo:
`ORDER BY t.completed ASC, t.dueDate ASC NULLS LAST, t.dueTime ASC NULLS LAST, t.createdAt ASC, t.id ASC`

Cuando `cursor != null` (`TaskCursor(cursorCompleted, cursorDueDate, cursorDueTime, cursorCreatedAt, cursorId)`):
- Sea `tieBreaker = "(t.createdAt > :cursorCreatedAt OR (t.createdAt = :cursorCreatedAt AND t.id > :cursorId))"`:
- **Rama `sameCompletedSeek`** según las 3 formas de `(cursorDueDate, cursorDueTime)`:
  1. `cursorDueDate != null && cursorDueTime != null`:
     ```sql
     (t.dueDate > :cursorDueDate OR t.dueDate IS NULL
      OR (t.dueDate = :cursorDueDate AND (
          t.dueTime > :cursorDueTime OR t.dueTime IS NULL
          OR (t.dueTime = :cursorDueTime AND <tieBreaker>)
      )))
     ```
  2. `cursorDueDate != null && cursorDueTime == null`:
     ```sql
     (t.dueDate > :cursorDueDate OR t.dueDate IS NULL
      OR (t.dueDate = :cursorDueDate AND t.dueTime IS NULL AND <tieBreaker>))
     ```
  3. `cursorDueDate == null`:
     ```sql
     (t.dueDate IS NULL AND <tieBreaker>)
     ```
- **Nivel 1 (`completed ASC`):**
  - Si `status == ALL && !cursorCompleted`: `AND (t.completed = true OR (t.completed = false AND <sameCompletedSeek>))`
  - En cualquier otro caso válido (`PENDING`, `COMPLETED`, o `ALL` con `cursorCompleted = true`): `AND t.completed = :cursorCompleted AND <sameCompletedSeek>`

Ejecuta `TypedQuery<Task>.setMaxResults(limit + 1)`. Si `tasks.size() > 0`, ejecuta `taskTagRepository.findByTaskIdsOrderByTagNameAndId(taskIds)` (1 sola consulta JPQL `JOIN FETCH tt.tag WHERE tt.task.id IN :taskIds ORDER BY tt.tag.name ASC, tt.tag.id ASC`) y agrupa las `TagResponse` por `taskId`.

---

## 5. Flujos de Negocio y Precedencia de Errores

### 5.1 Creación (`POST /api/v1/groups/{groupId}/tasks`) y Consulta (`GET /api/v1/tasks/{taskId}`)
1. **POST:** `@Valid CreateTaskRequest` (`400 invalidParams`) -> buscar `groupId` (`404` `"Grupo no encontrado"`) -> verificar `creator.id == userId` (`403`) -> `dateTimeProvider.validateDueDateAndTime(dueDate, dueTime)` (`400`, `TSK-REQ-03`) -> truncar `now` a `MICROS`, guardar `Task` (`completed = false`, `completedAt = null`, `assignedUser = null`), resolver/crear `tags` con `TagService.resolveOrCreateTags` e insertar `TaskTag` (`assignedAt = now`), llamar `syncDueSchedule(task, userZone, now)` -> `201 Created`.
2. **GET:** buscar `taskId` con `findByIdWithGroupAndCreator` (`404` `"Tarea no encontrada"`) -> verificar `creator.id == userId` (`403`) -> cargar `TaskTag` ordenados `(name ASC, id ASC)` -> calcular `overdue` con `ZoneId` del usuario -> `200 OK`.

### 5.2 Edición Parcial (`PATCH /api/v1/tasks/{taskId}`)
1. Validar campos presentes vía `TaskPatchValidator` (`400 invalidParams`).
2. Adquirir bloqueo pesimista `taskRepository.findByIdForUpdate(taskId)` (`404` `"Tarea no encontrada"`) -> verificar `creator.id == userId` (`403`).
3. Si todos los `PatchField` están omitidos (`{}`): no modificar ni guardar ni reprogramar; devolver `200 OK` (`TSK-REQ-07`).
4. Calcular `resultingDueDate` y `resultingDueTime`:
   - Si `dueDate` está presente y es `null`, y `dueTime` está omitido: limpiar ambos `resultingDueDate = null`, `resultingDueTime = null` (`EC-03`).
   - En otro caso usar el valor presente si `isPresent()`, o el valor actual de `task`.
   - Llamar `dateTimeProvider.validateDueDateAndTime(resultingDueDate, resultingDueTime)` (`400`, `TSK-REQ-03`).
5. Aplicar `title`, `description`, `resultingDueDate`, `resultingDueTime`; si `tags.isPresent()`, resolver/crear nuevo conjunto en `task.getGroup()` (`[]` si `null` o lista vacía), calcular diferencia o reemplazar enlaces en `task_tags` (sin borrar etiquetas de `tags`); actualizar `updatedAt = now`.
6. Si `dueDate.isPresent() || dueTime.isPresent()`: llamar `syncDueSchedule(task, userZone, now)` (si `task.isCompleted()` es `true`, cancela/mantiene sin job, nunca programa, `EC-02`).
7. Retornar `200 OK` con `TaskResponse` (`tags` ordenadas `name ASC, id ASC`).

### 5.3 Completado, Eliminación y Desasociación de Etiqueta
1. **`PATCH /api/v1/tasks/{taskId}/completion`:** `@Valid UpdateTaskCompletionRequest` (`400` si `completed` nulo/ausente, `TSK-REQ-09`) -> `findByIdForUpdate(taskId)` (`404` -> `403`) -> si `request.completed() == task.isCompleted()` retornar `200 OK` sin alterar `completedAt`; si cambia a `true`, fijar `completedAt = now`, `updatedAt = now`, `taskDueSchedulePort.cancel(taskId)`; si cambia a `false`, limpiar `completedAt = null`, `updatedAt = now`, `syncDueSchedule(task, userZone, now)`.
2. **`DELETE /api/v1/tasks/{taskId}`:** `findByIdForUpdate(taskId)` (`404` -> `403`) -> `taskDueSchedulePort.cancel(taskId)` -> `taskRepository.delete(task); taskRepository.flush();` (cascada en `task_tags`, `tags` preservadas) -> `204 No Content`.
3. **`DELETE /api/v1/tasks/{taskId}/tags/{tagId}` (`EC-06`):** `taskRepository.findByIdWithGroupAndCreator(taskId)` (`404` `"Tarea no encontrada"` -> `403`) -> `tagRepository.findByIdWithGroupAndCreator(tagId)` (`404` `"Etiqueta no encontrada"` -> `403`) -> buscar `TaskTagId(taskId, tagId)` en `taskTagRepository` (si no existe o el grupo difiere -> `404` `"La etiqueta no está asociada a esta tarea"`) -> eliminar enlace preservando `Tag` en `tags` -> `204 No Content`.

### 5.4 Mover y Copiar (`POST /move` y `POST /copy`)
Precedencia estricta (`TSK-REQ-14`):
1. `@Valid TaskTargetGroupRequest` (`400 invalidParams` sobre `targetGroupId`).
2. `findByIdForUpdate(taskId)` (en `move`) / `findByIdWithGroupAndCreator(taskId)` (en `copy`) -> `404` `"Tarea no encontrada"` -> `403`.
3. `groupRepository.findById(targetGroupId)` -> `404` `"Grupo no encontrado"` -> `403`.
4. **Move:** si `targetGroupId.equals(sourceGroup.id)` retorna `200 OK` sin cambios; si difiere, conserva `id`, `createdAt`, `completed`, `completedAt`, `dueDate`, `dueTime` y `scheduled_tasks`, resuelve/crea nombres de etiquetas en `targetGroup`, reemplaza enlaces en `task_tags`, actualiza `task.setGroup(targetGroup)` y `updatedAt = now` -> `200 OK`.
5. **Copy:** crea nueva `Task` en `targetGroup` con `createdAt = now`, `updatedAt = now`, copia `title`, `description`, `dueDate`, `dueTime`, `completed`, `completedAt`, resuelve/crea etiquetas en `targetGroup`, enlaza `TaskTag`, ejecuta `syncDueSchedule(copiedTask, userZone, now)` -> `201 Created`.

### 5.5 Listados Keyset (`GET /api/v1/tasks` y `GET /api/v1/groups/{groupId}/tasks`)
1. Validar `limit` (`1..50` -> `400`, `TSK-REQ-21`). Normalizar `status` (`PENDING` por defecto).
2. En `/groups/{groupId}/tasks`: validar existencia (`404`) y titularidad (`403`) de `groupId` usando `groupIds = List.of(groupId)`.
3. En `/tasks`: si `groupIds != null && !groupIds.isEmpty()`, deduplicar IDs no nulos y validar todos los grupos en bloque con `findAllByIdInWithCreator` (`404` si falta alguno, luego `403` si alguno es ajeno, `EC-08`).
4. Si `cursor != null`: decodificar `TaskCursor` con `keysetCursorCodec.decodeTaskCursor(cursor)` y verificar compatibilidad (`PENDING` rechaza `cursor.completed() == true`; `COMPLETED` rechaza `cursor.completed() == false` -> `400`, `TSK-REQ-20`).
5. Preparar `searchPattern` con `SqlLikePattern.containsIgnoreCase(search.trim())` si `search != null && !search.isBlank()`.
6. Consultar `limit + 1` tareas vía `TaskQueryRepository`, cargar sus etiquetas en bloque ordenado, calcular `nextCursor` desde el elemento `limit - 1` si `results.size() > limit`, y devolver `KeysetPage.of(responses, limit, nextCursor)`.

---

## 6. Estrategia de Pruebas Automatizadas

1. **Pruebas Unitarias Puras:**
   - **`KeysetCursorCodecTest`:** rechazar `TaskCursor` con `completed == null`, `createdAt == null`, `id == null` o `dueTime != null && dueDate == null`, tanto en constructor como en token firmado con checksum válido (`TSK-REQ-20`, `EC-09`).
   - **`SqlLikePatternTest`:** validar escape literal de `!`, `%`, `_` y preservación de `\` para `containsIgnoreCase` y `startsWithIgnoreCase` (`EC-08`).
   - **`TagNameRulesTest` / `TagMapperTest`:** normalización `trim()`, longitudes `1..30`, rechazo `,`/`;`, deduplicación por `toLowerCase(Locale.ROOT)` y mapeo `TagResponse`.
   - **`UpdateTaskRequestDeserializerTest` / `TaskMapperTest`:** distinguir ausencia vs `null` explícito vs string blanco en `description`, rechazo de tipos JSON inválidos (`"title": 123`, `"tags": "abc"`), ignorar propiedades desconocidas (`EC-11`), y verificar `overdue = false` cuando `completed = true` aunque la fecha sea pasada (`TSK-REQ-05`, `EC-02`).
2. **Pruebas de Integración `@SpringBootTest` + `Testcontainers` (`postgres:alpine`) con `ddl-auto=validate`:**
   - **`TagEndpointsIntegrationTest`:** `GET/POST/DELETE /api/v1/groups/{groupId}/tags`, `201` vs `200` idempotente, aislamiento y precedencia `404`/`403` (`EC-06`), prefix `query` con `%`, `_` y `\` (`EC-08`), `TagCursor`, cascada en `task_tags` (`TAG-REQ-08`) y carrera concurrente con `CountDownLatch` (`EC-10`).
   - **`TaskCreationAndDetailIntegrationTest`:** `POST /groups/{groupId}/tasks` y `GET /tasks/{taskId}`, `tags: null` -> `[]`, deduplicación (`EC-05`), `EC-01` con `Clock` determinista (`@MockitoBean Clock` con stub inicial en `@BeforeEach` para antes/después de 09:00 AM y cruce de `23:59:59.999`), inyección de campos controlados por el servidor (`EC-11`), `400`/`401`/`403`/`404`.
   - **`TaskUpdateIntegrationTest`:** `PATCH /tasks/{taskId}`, `{}` sin efecto, limpieza explícita `description`/`dueDate`/`dueTime`/`tags` (`EC-03`), reemplazo de etiquetas sin borrar del grupo (`EC-04`), rechazo `dueTime` sin `dueDate`, edición de tarea completada sin programar job (`EC-02`) y concurrencia `PATCH` (`EC-10`).
   - **`TaskCompletionAndDeletionIntegrationTest`:** `PATCH /completion` (transiciones e idempotencia, cancelación/reprogramación en `scheduled_tasks`, `400` sin `completed`), `DELETE /tasks/{taskId}` con limpieza selectiva en `scheduled_tasks` y preservación de `tags`, y `DELETE /tasks/{taskId}/tags/{tagId}` con matriz completa de precedencia `EC-06`.
   - **`TaskMoveAndCopyIntegrationTest`:** `POST /move` (idempotencia mismo grupo, conservación `createdAt` y `scheduled_tasks`, resolución de etiquetas en destino), `POST /copy` (nuevo `id`, nuevo `createdAt`, programación de nuevo job `task-due-{newId}`), y precedencia estricta `400` -> `{taskId}` -> `targetGroupId` (`TSK-REQ-14`, `EC-07`).
   - **`TaskPaginationAndFilterIntegrationTest`:** `GET /tasks` y `GET /groups/{groupId}/tasks` recorriendo múltiples páginas con `limit` pequeño a través de las 3 variantes de `(dueDate, dueTime)` + transición `false -> true` en `ALL`, desempate por `(created_at, id)`, filtros combinados `status`/`search`/`groupIds` (`?groupIds=` vacío, duplicados, precedencia `404` antes `403`, comodines SQL), rechazo de cursores incompatibles con `status` y páginas vacías ante cursores desplazados (`EC-08`, `EC-09`).

---

## 7. Matriz de Cobertura de Requerimientos

| Requisito EARS / Caso Borde | Componente / Archivo de Implementación | Clase de Prueba de Verificación |
| :--- | :--- | :--- |
| **TSK-REQ-01** a **06** | `Task`, `TaskTag`, `CreateTaskRequest`, `TaskMapper`, `TaskService`, `TaskController` | `TaskMapperTest`, `TaskCreationAndDetailIntegrationTest` |
| **TSK-REQ-07** | `PatchField`, `UpdateTaskRequest`, `UpdateTaskRequestDeserializer`, `TaskPatchValidator`, `TaskService.updateTask()` | `UpdateTaskRequestDeserializerTest`, `TaskUpdateIntegrationTest` |
| **TSK-REQ-08**, **09** | `UpdateTaskCompletionRequest`, `TaskService.updateCompletion()`, `TaskController` | `TaskCompletionAndDeletionIntegrationTest` |
| **TSK-REQ-10**, **11** | `TaskRepository.findByIdForUpdate()`, `TaskDueSchedulePort`, `JdbcTaskDueScheduleAdapter`, `TaskService` | `TaskCreationAndDetailIntegrationTest`, `TaskUpdateIntegrationTest`, `TaskCompletionAndDeletionIntegrationTest`, `TaskMoveAndCopyIntegrationTest` |
| **TSK-REQ-12** a **14** | `TaskTargetGroupRequest`, `TaskService.moveTask()`, `TaskService.copyTask()` | `TaskMoveAndCopyIntegrationTest` |
| **TSK-REQ-15** a **21** | `TaskStatusFilter`, `TaskQueryRepositoryImpl`, `TaskTagRepository`, `KeysetCursor.TaskCursor`, `KeysetCursorCodec`, `SqlLikePattern`, `GroupRepository` | `KeysetCursorCodecTest`, `SqlLikePatternTest`, `TaskPaginationAndFilterIntegrationTest` |
| **TAG-REQ-01** a **09** | `Tag`, `TagNameRules`, `TagRepository`, `TagMapper`, `TagService`, `TagController`, `TaskService.detachTagFromTask()` | `TagNameRulesTest`, `TagMapperTest`, `TagEndpointsIntegrationTest`, `TaskCompletionAndDeletionIntegrationTest` |
| **TSK-REQ-22** a **27** | `FieldValidationException`, `GlobalExceptionHandler`, `SecurityConfig`, `RestAccessDeniedHandler` | `GlobalExceptionHandlerTest`, todas las suites `*IntegrationTest` |
| **EC-01** a **EC-05** | `DateTimeProvider`, `TaskMapper`, `TaskService`, `TaskPatchValidator`, `CreateTaskRequest` | `TaskCreationAndDetailIntegrationTest`, `TaskUpdateIntegrationTest`, `TaskCompletionAndDeletionIntegrationTest` |
| **EC-06**, **EC-07** | `TagService.deleteGroupTag()`, `TaskService` (`detachTagFromTask`, `moveTask`, `copyTask`) | `TagEndpointsIntegrationTest`, `TaskCompletionAndDeletionIntegrationTest`, `TaskMoveAndCopyIntegrationTest` |
| **EC-08**, **EC-09** | `SqlLikePattern`, `TaskQueryRepositoryImpl`, `KeysetCursor.TaskCursor`, `KeysetCursorCodec`, `GroupRepository` | `SqlLikePatternTest`, `KeysetCursorCodecTest`, `TagEndpointsIntegrationTest`, `TaskPaginationAndFilterIntegrationTest` |
| **EC-10**, **EC-11** | `TagService` (`ON CONFLICT DO NOTHING`), `TaskRepository.findByIdForUpdate()`, `@JsonIgnoreProperties` / `UpdateTaskRequestDeserializer` | `TagEndpointsIntegrationTest`, `TaskCreationAndDetailIntegrationTest`, `TaskUpdateIntegrationTest` |
