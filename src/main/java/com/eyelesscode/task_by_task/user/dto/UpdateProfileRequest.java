package com.eyelesscode.task_by_task.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record UpdateProfileRequest(
        @Size(
                        min = 2,
                        max = 50,
                        message = "El nombre para mostrar debe tener entre 2 y 50 caracteres")
                String displayName,
        @Pattern(
                        regexp = "^[A-Za-z0-9]{3,30}$",
                        message =
                                "El nombre de usuario debe tener entre 3 y 30 caracteres"
                                        + " alfanuméricos")
                String username,
        @Email(message = "El formato del correo electrónico no es válido") @Size(
                        min = 3,
                        max = 255,
                        message = "El correo electrónico debe tener entre 3 y 255 caracteres")
                String email,
        String timezone,
        String timeFormat) {

    public UpdateProfileRequest {
        if (displayName != null) {
            displayName = displayName.trim();
        }
        if (username != null) {
            username = username.trim();
        }
        if (email != null) {
            email = email.trim().toLowerCase(Locale.ROOT);
        }
        if (timezone != null) {
            timezone = timezone.trim();
        }
        if (timeFormat != null) {
            timeFormat = timeFormat.trim();
        }
    }
}
