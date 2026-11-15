package com.eyelesscode.task_by_task.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "La contraseña actual es obligatoria") String currentPassword,
        @NotBlank(message = "La nueva contraseña es obligatoria") @Size(
                        min = 6,
                        max = 30,
                        message = "La nueva contraseña debe tener entre 6 y 30 caracteres")
                String newPassword) {}
