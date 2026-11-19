package com.eyelesscode.task_by_task.task.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Request payload for toggling a task's completion status. */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Solicitud para marcar o desmarcar una tarea como completada")
public record UpdateTaskCompletionRequest(
        @Schema(description = "Nuevo estado de completitud (true = completada, false = pendiente)")
                @NotNull(message = "El estado de completado es obligatorio") Boolean completed) {}
