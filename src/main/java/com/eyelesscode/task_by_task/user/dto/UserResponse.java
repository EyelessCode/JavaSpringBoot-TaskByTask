package com.eyelesscode.task_by_task.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Perfil público del usuario autenticado")
public record UserResponse(
        @Schema(description = "Identificador único del usuario", example = "1") Long id,
        @Schema(description = "Correo electrónico normalizado", example = "ana@example.com")
                String email,
        @Schema(description = "Nombre de usuario único", example = "AnaDev2026") String username,
        @Schema(description = "Nombre visible del usuario", example = "Ana García")
                String displayName,
        @Schema(description = "Zona horaria IANA del usuario", example = "America/Lima")
                String timezone,
        @Schema(
                        description = "Formato horario preferido",
                        allowableValues = {"24H", "12H"},
                        example = "24H")
                String timeFormat,
        @Schema(description = "Instante UTC de creación de la cuenta") Instant createdAt,
        @Schema(
                        description = "Cantidad de códigos de recuperación aún disponibles (0 a 8)",
                        example = "8")
                int remainingRecoveryCodes) {}
