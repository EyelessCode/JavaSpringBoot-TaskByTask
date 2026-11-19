package com.eyelesscode.task_by_task.task;

import com.eyelesscode.task_by_task.shared.config.OpenApiConfig;
import com.eyelesscode.task_by_task.shared.pagination.KeysetPage;
import com.eyelesscode.task_by_task.task.dto.CreateTaskRequest;
import com.eyelesscode.task_by_task.task.dto.TaskResponse;
import com.eyelesscode.task_by_task.task.dto.TaskTargetGroupRequest;
import com.eyelesscode.task_by_task.task.dto.UpdateTaskCompletionRequest;
import com.eyelesscode.task_by_task.task.dto.UpdateTaskRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST controller exposing task creation, update, lifecycle, and query endpoints. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Tasks", description = "Gestión de tareas del usuario, filtros y paginación Keyset")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/groups/{groupId}/tasks")
    @Operation(
            summary = "Crear una tarea en un grupo",
            description =
                    "Permite crear una tarea sin fecha, solo con fecha (aviso 09:00 AM / vencida al"
                            + " fin del día 23:59:59.999 hora local) o con fecha y hora. Fechas"
                            + " pasadas son válidas (nace vencida).")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Tarea creada correctamente"),
        @ApiResponse(
                responseCode = "400",
                description = "Error de validación o fecha/hora inválida"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido"),
        @ApiResponse(responseCode = "404", description = "Grupo no encontrado"),
        @ApiResponse(responseCode = "415", description = "Tipo de medio no soportado")
    })
    public ResponseEntity<TaskResponse> createTask(
            @Parameter(description = "Identificador del grupo destino", example = "1") @PathVariable
                    Long groupId,
            @Valid @RequestBody CreateTaskRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(taskService.createTask(groupId, request));
    }

    @GetMapping("/tasks/{taskId}")
    @SecurityRequirements({@SecurityRequirement(name = OpenApiConfig.SECURITY_SESSION_COOKIE)})
    @Operation(summary = "Consultar el detalle de una tarea")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Detalle de la tarea"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(responseCode = "404", description = "Tarea no encontrada")
    })
    public ResponseEntity<TaskResponse> getTask(
            @Parameter(description = "Identificador de la tarea", example = "42") @PathVariable
                    Long taskId) {
        return ResponseEntity.ok(taskService.getTask(taskId));
    }

    @PatchMapping("/tasks/{taskId}")
    @Operation(
            summary = "Actualizar parcialmente una tarea",
            description =
                    "Actualiza únicamente los campos enviados en el JSON. `description`, `dueDate`,"
                            + " `dueTime` y `tags` aceptan `null` para limpiarse; `title` no puede"
                            + " ser nulo. Reprograma o cancela el aviso de vencimiento si cambia"
                            + " la fecha.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Tarea actualizada correctamente"),
        @ApiResponse(responseCode = "400", description = "Error de validación o JSON inválido"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido"),
        @ApiResponse(responseCode = "404", description = "Tarea no encontrada"),
        @ApiResponse(responseCode = "415", description = "Tipo de medio no soportado")
    })
    public ResponseEntity<TaskResponse> updateTask(
            @Parameter(description = "Identificador de la tarea", example = "42") @PathVariable
                    Long taskId,
            @RequestBody UpdateTaskRequest request) {
        return ResponseEntity.ok(taskService.updateTask(taskId, request));
    }

    @PatchMapping("/tasks/{taskId}/completion")
    @Operation(
            summary = "Marcar o desmarcar una tarea como completada",
            description =
                    "Al completar cancela recordatorios pendientes; al reabrir reprograma el aviso"
                            + " si la fecha límite aún está en el futuro.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Estado de completitud actualizado"),
        @ApiResponse(responseCode = "400", description = "Error de validación"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido"),
        @ApiResponse(responseCode = "404", description = "Tarea no encontrada"),
        @ApiResponse(responseCode = "415", description = "Tipo de medio no soportado")
    })
    public ResponseEntity<TaskResponse> updateCompletion(
            @Parameter(description = "Identificador de la tarea", example = "42") @PathVariable
                    Long taskId,
            @Valid @RequestBody UpdateTaskCompletionRequest request) {
        return ResponseEntity.ok(taskService.updateCompletion(taskId, request));
    }

    @DeleteMapping("/tasks/{taskId}")
    @Operation(summary = "Eliminar permanentemente una tarea y su recordatorio programado")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Tarea eliminada correctamente"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido"),
        @ApiResponse(responseCode = "404", description = "Tarea no encontrada")
    })
    public ResponseEntity<Void> deleteTask(
            @Parameter(description = "Identificador de la tarea", example = "42") @PathVariable
                    Long taskId) {
        taskService.deleteTask(taskId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/tasks/{taskId}/tags/{tagId}")
    @Operation(
            summary = "Desasociar una etiqueta de una tarea sin eliminarla del grupo",
            description = "Quita el vínculo entre la tarea y la etiqueta conservando esta última.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Etiqueta desasociada de la tarea"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido"),
        @ApiResponse(responseCode = "404", description = "Tarea o etiqueta no encontrada")
    })
    public ResponseEntity<Void> detachTagFromTask(
            @Parameter(description = "Identificador de la tarea", example = "42") @PathVariable
                    Long taskId,
            @Parameter(description = "Identificador de la etiqueta", example = "10") @PathVariable
                    Long tagId) {
        taskService.detachTagFromTask(taskId, tagId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tasks/{taskId}/move")
    @Operation(
            summary = "Mover una tarea a otro grupo del usuario",
            description =
                    "Traslada la tarea al grupo destino y resuelve/crea sus etiquetas equivalentes"
                            + " en el grupo destino.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Tarea movida correctamente"),
        @ApiResponse(responseCode = "400", description = "Error de validación"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido"),
        @ApiResponse(responseCode = "404", description = "Tarea o grupo destino no encontrado"),
        @ApiResponse(responseCode = "415", description = "Tipo de medio no soportado")
    })
    public ResponseEntity<TaskResponse> moveTask(
            @Parameter(description = "Identificador de la tarea", example = "42") @PathVariable
                    Long taskId,
            @Valid @RequestBody TaskTargetGroupRequest request) {
        return ResponseEntity.ok(taskService.moveTask(taskId, request));
    }

    @PostMapping("/tasks/{taskId}/copy")
    @Operation(
            summary = "Copiar una tarea a un grupo del usuario",
            description =
                    "Duplica la tarea (incluido en el mismo grupo) como pendiente y replica sus"
                            + " etiquetas en el grupo destino.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Tarea copiada correctamente"),
        @ApiResponse(responseCode = "400", description = "Error de validación"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido"),
        @ApiResponse(responseCode = "404", description = "Tarea o grupo destino no encontrado"),
        @ApiResponse(responseCode = "415", description = "Tipo de medio no soportado")
    })
    public ResponseEntity<TaskResponse> copyTask(
            @Parameter(description = "Identificador de la tarea", example = "42") @PathVariable
                    Long taskId,
            @Valid @RequestBody TaskTargetGroupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(taskService.copyTask(taskId, request));
    }

    @GetMapping("/tasks")
    @SecurityRequirements({@SecurityRequirement(name = OpenApiConfig.SECURITY_SESSION_COOKIE)})
    @Operation(
            summary = "Listar tareas del usuario con paginación Keyset y filtros combinados",
            description =
                    "Orden fijo: `completed ASC` (en `ALL`), `dueDate ASC NULLS LAST`, `createdAt"
                            + " ASC`, `id ASC`. Permite filtrar por múltiples `groupIds`, estado"
                            + " (`PENDING`, `COMPLETED`, `ALL`) y búsqueda por texto.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado paginado de tareas"),
        @ApiResponse(responseCode = "400", description = "Cursor, límite o filtro inválido"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado")
    })
    public ResponseEntity<KeysetPage<TaskResponse>> listTasks(
            @Parameter(description = "Lista opcional de IDs de grupos a incluir")
                    @RequestParam(required = false)
                    List<Long> groupIds,
            @Parameter(
                            description = "Filtro de estado de tareas (PENDING, COMPLETED, ALL)",
                            example = "PENDING")
                    @RequestParam(defaultValue = "PENDING")
                    TaskStatusFilter status,
            @Parameter(description = "Texto de búsqueda por título, descripción o etiqueta")
                    @RequestParam(required = false)
                    String search,
            @Parameter(description = "Cursor opaco Base64URL de la página anterior")
                    @RequestParam(required = false)
                    String cursor,
            @Parameter(description = "Cantidad máxima de elementos por página (1 a 100)")
                    @RequestParam(defaultValue = "20")
                    int limit) {
        return ResponseEntity.ok(taskService.listTasks(groupIds, status, search, cursor, limit));
    }

    @GetMapping("/groups/{groupId}/tasks")
    @SecurityRequirements({@SecurityRequirement(name = OpenApiConfig.SECURITY_SESSION_COOKIE)})
    @Operation(summary = "Listar tareas de un grupo específico con paginación Keyset y filtros")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado paginado de tareas del grupo"),
        @ApiResponse(responseCode = "400", description = "Cursor, límite o filtro inválido"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(responseCode = "404", description = "Grupo no encontrado")
    })
    public ResponseEntity<KeysetPage<TaskResponse>> listGroupTasks(
            @Parameter(description = "Identificador del grupo", example = "1") @PathVariable
                    Long groupId,
            @Parameter(
                            description = "Filtro de estado de tareas (PENDING, COMPLETED, ALL)",
                            example = "PENDING")
                    @RequestParam(defaultValue = "PENDING")
                    TaskStatusFilter status,
            @Parameter(description = "Texto de búsqueda por título, descripción o etiqueta")
                    @RequestParam(required = false)
                    String search,
            @Parameter(description = "Cursor opaco Base64URL de la página anterior")
                    @RequestParam(required = false)
                    String cursor,
            @Parameter(description = "Cantidad máxima de elementos por página (1 a 100)")
                    @RequestParam(defaultValue = "20")
                    int limit) {
        return ResponseEntity.ok(
                taskService.listGroupTasks(groupId, status, search, cursor, limit));
    }
}
