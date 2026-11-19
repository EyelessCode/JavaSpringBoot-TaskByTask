package com.eyelesscode.task_by_task.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Solicitud para cambiar la contraseña del usuario autenticado")
public record ChangePasswordRequest(
        @Schema(description = "Contraseña actual del usuario")
                @NotBlank(message = "La contraseña actual es obligatoria") String currentPassword,
        @Schema(description = "Nueva contraseña (6 a 30 caracteres)", example = "NuevaClave123")
                @NotBlank(message = "La nueva contraseña es obligatoria") @Size(
                        min = 6,
                        max = 30,
                        message = "La nueva contraseña debe tener entre 6 y 30 caracteres")
                String newPassword) {}
