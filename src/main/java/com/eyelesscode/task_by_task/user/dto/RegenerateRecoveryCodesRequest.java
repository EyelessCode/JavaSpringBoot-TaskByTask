package com.eyelesscode.task_by_task.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Confirmación de contraseña para regenerar los 8 códigos de recuperación")
public record RegenerateRecoveryCodesRequest(
        @Schema(description = "Contraseña actual del usuario")
                @NotBlank(message = "La contraseña actual es obligatoria") String currentPassword) {}
