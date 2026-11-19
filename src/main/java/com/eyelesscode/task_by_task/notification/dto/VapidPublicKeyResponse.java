package com.eyelesscode.task_by_task.notification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** Response payload for {@code GET /api/v1/notifications/push/vapid-public-key}. */
@Schema(description = "Estado de Web Push y clave pública VAPID del servidor")
public record VapidPublicKeyResponse(
        @Schema(description = "Indica si el envío de notificaciones Web Push está activo")
                boolean enabled,
        @Schema(
                        description =
                                "Clave pública VAPID en Base64URL (nula cuando Web Push está"
                                        + " deshabilitado)",
                        nullable = true)
                @JsonInclude(JsonInclude.Include.ALWAYS)
                String publicKey) {}
