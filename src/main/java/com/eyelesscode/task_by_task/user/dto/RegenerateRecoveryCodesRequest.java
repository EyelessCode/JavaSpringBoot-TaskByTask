package com.eyelesscode.task_by_task.user.dto;

import jakarta.validation.constraints.NotBlank;

public record RegenerateRecoveryCodesRequest(
        @NotBlank(message = "La contraseña actual es obligatoria") String currentPassword) {}
