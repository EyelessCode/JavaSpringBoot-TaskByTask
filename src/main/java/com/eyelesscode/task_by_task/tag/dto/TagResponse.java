package com.eyelesscode.task_by_task.tag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Representación de una etiqueta perteneciente a un grupo")
public record TagResponse(
        @Schema(description = "Identificador único de la etiqueta", example = "10") Long id,
        @Schema(description = "Identificador del grupo al que pertenece la etiqueta", example = "1")
                Long groupId,
        @Schema(description = "Nombre de la etiqueta", example = "urgente") String name,
        @Schema(description = "Instante UTC de creación de la etiqueta") Instant createdAt) {}
