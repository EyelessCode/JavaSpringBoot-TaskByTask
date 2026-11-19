package com.eyelesscode.task_by_task.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Credenciales para iniciar sesión")
public record LoginRequest(
        @Schema(
                        description = "Correo electrónico registrado (máx. 255 caracteres)",
                        example = "ana@example.com")
                @NotBlank(message = "El correo electrónico es obligatorio") @Email(message = "El formato del correo electrónico no es válido") @Size(
                        max = 255,
                        message = "El correo electrónico no puede exceder los 255 caracteres")
                String email,
        @Schema(description = "Contraseña del usuario", example = "Secreta123")
                @NotBlank(message = "La contraseña es obligatoria") String password,
        @Schema(
                        description =
                                "Zona horaria IANA opcional para sincronizar al iniciar sesión",
                        example = "America/Lima",
                        nullable = true)
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
