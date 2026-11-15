package com.eyelesscode.task_by_task.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record RecoverPasswordRequest(
        @NotBlank(message = "El correo electrónico es obligatorio") @Email(message = "El formato del correo electrónico no es válido") @Size(
                        max = 255,
                        message = "El correo electrónico no puede exceder los 255 caracteres")
                String email,
        @NotBlank(message = "El código de recuperación es obligatorio") String recoveryCode,
        @NotBlank(message = "La nueva contraseña es obligatoria") @Size(
                        min = 6,
                        max = 30,
                        message = "La nueva contraseña debe tener entre 6 y 30 caracteres")
                String newPassword) {

    public RecoverPasswordRequest {
        if (email != null) {
            email = email.trim().toLowerCase(Locale.ROOT);
        }
        if (recoveryCode != null) {
            recoveryCode = recoveryCode.trim();
        }
    }
}
