package com.eyelesscode.task_by_task.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Representación de un grupo de tareas")
public record GroupResponse(
        @Schema(description = "Identificador único del grupo", example = "1") Long id,
        @Schema(description = "Nombre del grupo", example = "Personal") String name,
        @Schema(description = "Indica si es el grupo predeterminado Personal (no eliminable)")
                boolean isPersonal,
        @Schema(description = "Identificador del usuario creador del grupo", example = "1")
                Long creatorId,
        @Schema(description = "Instante UTC de creación del grupo") Instant createdAt,
        @Schema(description = "Instante UTC de última actualización del grupo")
                Instant updatedAt) {}
