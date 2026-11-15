package com.eyelesscode.task_by_task.task;

import com.eyelesscode.task_by_task.task.dto.CreateTaskRequest;
import com.eyelesscode.task_by_task.task.dto.TaskResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
