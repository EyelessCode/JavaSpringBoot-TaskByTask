package com.eyelesscode.task_by_task.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Actualización parcial del perfil del usuario")
public record UpdateProfileRequest(
        @Schema(
                        description = "Nuevo nombre para mostrar (2 a 50 caracteres)",
                        example = "Ana García",
                        nullable = true)
                @Size(
                        min = 2,
                        max = 50,
                        message = "El nombre para mostrar debe tener entre 2 y 50 caracteres")
                String displayName,
        @Schema(
                        description =
                                "Nuevo nombre de usuario alfanumérico único (3 a 30 caracteres)",
                        example = "AnaDev2026",
                        nullable = true)
                @Pattern(
                        regexp = "^[A-Za-z0-9]{3,30}$",
                        message =
                                "El nombre de usuario debe tener entre 3 y 30 caracteres"
                                        + " alfanuméricos")
                String username,
        @Schema(
                        description = "Nuevo correo electrónico único (3 a 255 caracteres)",
                        example = "ana@example.com",
                        nullable = true)
                @Email(message = "El formato del correo electrónico no es válido") @Size(
                        min = 3,
                        max = 255,
                        message = "El correo electrónico debe tener entre 3 y 255 caracteres")
                String email,
        @Schema(description = "Zona horaria IANA válida", example = "America/Lima", nullable = true)
                String timezone,
        @Schema(
                        description = "Preferencia de formato horario",
                        allowableValues = {"24H", "12H"},
                        example = "24H",
                        nullable = true)
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
