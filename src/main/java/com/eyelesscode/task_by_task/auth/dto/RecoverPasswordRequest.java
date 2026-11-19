package com.eyelesscode.task_by_task.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Solicitud de restablecimiento de contraseña mediante código de un solo uso")
public record RecoverPasswordRequest(
        @Schema(description = "Correo electrónico de la cuenta", example = "ana@example.com")
                @NotBlank(message = "El correo electrónico es obligatorio") @Email(message = "El formato del correo electrónico no es válido") @Size(
                        max = 255,
                        message = "El correo electrónico no puede exceder los 255 caracteres")
                String email,
        @Schema(
                        description = "Código de recuperación de un solo uso (formato XXXX-XXXX)",
                        example = "A1B2-C3D4")
                @NotBlank(message = "El código de recuperación es obligatorio") String recoveryCode,
        @Schema(description = "Nueva contraseña (6 a 30 caracteres)", example = "NuevaClave123")
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
