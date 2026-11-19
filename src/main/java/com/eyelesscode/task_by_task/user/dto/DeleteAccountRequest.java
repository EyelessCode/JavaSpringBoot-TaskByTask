package com.eyelesscode.task_by_task.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DeleteAccountRequest(
        @NotBlank(message = "La contraseña es obligatoria") String password) {}
