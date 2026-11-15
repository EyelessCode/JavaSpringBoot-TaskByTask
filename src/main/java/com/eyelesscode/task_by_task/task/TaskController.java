package com.eyelesscode.task_by_task.task;

import com.eyelesscode.task_by_task.shared.pagination.KeysetPage;
import com.eyelesscode.task_by_task.task.dto.CreateTaskRequest;
import com.eyelesscode.task_by_task.task.dto.TaskResponse;
import com.eyelesscode.task_by_task.task.dto.TaskTargetGroupRequest;
import com.eyelesscode.task_by_task.task.dto.UpdateTaskCompletionRequest;
import com.eyelesscode.task_by_task.task.dto.UpdateTaskRequest;
import io.swagger.v3.oas.annotations.Operation;
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

/** REST controller exposing task creation and detail endpoints. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Tasks", description = "Gestión de tareas del usuario")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/groups/{groupId}/tasks")
    @Operation(summary = "Crear una tarea en un grupo")
    public ResponseEntity<TaskResponse> createTask(
            @PathVariable Long groupId, @Valid @RequestBody CreateTaskRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(taskService.createTask(groupId, request));
    }

    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "Consultar el detalle de una tarea")
    public ResponseEntity<TaskResponse> getTask(@PathVariable Long taskId) {
        return ResponseEntity.ok(taskService.getTask(taskId));
    }

    @PatchMapping("/tasks/{taskId}")
    @Operation(summary = "Actualizar parcialmente una tarea")
    public ResponseEntity<TaskResponse> updateTask(
            @PathVariable Long taskId, @RequestBody UpdateTaskRequest request) {
        return ResponseEntity.ok(taskService.updateTask(taskId, request));
    }

    @PatchMapping("/tasks/{taskId}/completion")
    @Operation(summary = "Marcar o desmarcar una tarea como completada")
    public ResponseEntity<TaskResponse> updateCompletion(
            @PathVariable Long taskId, @Valid @RequestBody UpdateTaskCompletionRequest request) {
        return ResponseEntity.ok(taskService.updateCompletion(taskId, request));
    }

    @DeleteMapping("/tasks/{taskId}")
    @Operation(summary = "Eliminar una tarea")
    public ResponseEntity<Void> deleteTask(@PathVariable Long taskId) {
        taskService.deleteTask(taskId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/tasks/{taskId}/tags/{tagId}")
    @Operation(summary = "Desasociar una etiqueta de una tarea")
    public ResponseEntity<Void> detachTagFromTask(
            @PathVariable Long taskId, @PathVariable Long tagId) {
        taskService.detachTagFromTask(taskId, tagId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tasks/{taskId}/move")
    @Operation(summary = "Mover una tarea a otro grupo")
    public ResponseEntity<TaskResponse> moveTask(
            @PathVariable Long taskId, @Valid @RequestBody TaskTargetGroupRequest request) {
        return ResponseEntity.ok(taskService.moveTask(taskId, request));
    }

    @PostMapping("/tasks/{taskId}/copy")
    @Operation(summary = "Copiar una tarea a un grupo")
    public ResponseEntity<TaskResponse> copyTask(
            @PathVariable Long taskId, @Valid @RequestBody TaskTargetGroupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(taskService.copyTask(taskId, request));
    }

    @GetMapping("/tasks")
    @Operation(summary = "Listar tareas del usuario con paginación Keyset y filtros")
    public ResponseEntity<KeysetPage<TaskResponse>> listTasks(
            @RequestParam(required = false) List<Long> groupIds,
            @RequestParam(defaultValue = "PENDING") TaskStatusFilter status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(taskService.listTasks(groupIds, status, search, cursor, limit));
    }

    @GetMapping("/groups/{groupId}/tasks")
    @Operation(summary = "Listar tareas de un grupo con paginación Keyset y filtros")
    public ResponseEntity<KeysetPage<TaskResponse>> listGroupTasks(
            @PathVariable Long groupId,
            @RequestParam(defaultValue = "PENDING") TaskStatusFilter status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(
                taskService.listGroupTasks(groupId, status, search, cursor, limit));
    }
}
