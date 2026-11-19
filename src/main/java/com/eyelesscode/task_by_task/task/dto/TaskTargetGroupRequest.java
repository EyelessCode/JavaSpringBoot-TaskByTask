package com.eyelesscode.task_by_task.task.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Request payload specifying the target group for moving or copying a task. */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Grupo destino para mover o copiar una tarea")
public record TaskTargetGroupRequest(
        @Schema(description = "Identificador del grupo destino", example = "2")
                @NotNull(message = "El grupo destino es obligatorio") Long targetGroupId) {}
