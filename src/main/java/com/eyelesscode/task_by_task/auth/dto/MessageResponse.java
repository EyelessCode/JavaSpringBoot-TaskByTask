package com.eyelesscode.task_by_task.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Respuesta informativa con mensaje en español")
public record MessageResponse(
        @Schema(
                        description = "Mensaje descriptivo del resultado de la operación",
                        example = "Contraseña actualizada exitosamente")
                String message) {}
