package com.eyelesscode.task_by_task.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Datos para registrar una nueva cuenta de usuario")
public record RegisterRequest(
        @Schema(
                        description = "Correo electrónico único (máx. 255 caracteres)",
                        example = "ana@example.com")
                @NotBlank(message = "El correo electrónico es obligatorio") @Email(message = "El formato del correo electrónico no es válido") @Size(
                        max = 255,
                        message = "El correo electrónico no puede exceder los 255 caracteres")
                String email,
        @Schema(
                        description = "Nombre de usuario único alfanumérico (3 a 30 caracteres)",
                        example = "AnaDev2026")
                @NotBlank(message = "El nombre de usuario es obligatorio") @Pattern(
                        regexp = "^[A-Za-z0-9]{3,30}$",
                        message =
                                "El nombre de usuario debe tener entre 3 y 30 caracteres"
                                        + " alfanuméricos")
                String username,
        @Schema(
                        description = "Nombre visible del usuario (2 a 50 caracteres)",
                        example = "Ana García")
                @NotBlank(message = "El nombre para mostrar es obligatorio") @Size(
                        min = 2,
                        max = 50,
                        message = "El nombre para mostrar debe tener entre 2 y 50 caracteres")
                String displayName,
        @Schema(description = "Contraseña (6 a 30 caracteres)", example = "Secreta123")
                @NotBlank(message = "La contraseña es obligatoria") @Size(
                        min = 6,
                        max = 30,
                        message = "La contraseña debe tener entre 6 y 30 caracteres")
                String password,
        @Schema(
                        description = "Zona horaria IANA opcional (por defecto UTC)",
                        example = "America/Lima",
                        nullable = true)
                String timezone) {

    public RegisterRequest {
        if (email != null) {
            email = email.trim().toLowerCase(Locale.ROOT);
        }
        if (username != null) {
            username = username.trim();
        }
        if (displayName != null) {
            displayName = displayName.trim();
        }
        if (timezone != null) {
            timezone = timezone.trim();
        }
    }
}
