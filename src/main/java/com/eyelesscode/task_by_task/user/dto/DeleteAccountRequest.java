package com.eyelesscode.task_by_task.user.dto;

import jakarta.validation.constraints.NotBlank;

public record DeleteAccountRequest(
        @NotBlank(message = "La contraseña es obligatoria") String password) {}
