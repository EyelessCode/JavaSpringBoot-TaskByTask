package com.eyelesscode.task_by_task.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record LoginRequest(
        @NotBlank(message = "El correo electrónico es obligatorio") @Email(message = "El formato del correo electrónico no es válido") @Size(
                        max = 255,
                        message = "El correo electrónico no puede exceder los 255 caracteres")
                String email,
        @NotBlank(message = "La contraseña es obligatoria") String password,
        String timezone) {

    public LoginRequest {
        if (email != null) {
            email = email.trim().toLowerCase(Locale.ROOT);
        }
        if (timezone != null) {
            timezone = timezone.trim();
        }
    }
}
