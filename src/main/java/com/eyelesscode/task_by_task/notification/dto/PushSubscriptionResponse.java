package com.eyelesscode.task_by_task.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** API response representation of a registered push subscription (omitting cryptographic keys). */
@Schema(description = "Suscripción Web Push registrada (omite material criptográfico)")
public record PushSubscriptionResponse(
        @Schema(description = "Identificador único de la suscripción", example = "5") Long id,
        @Schema(
                        description = "URL del endpoint Web Push del navegador",
                        example = "https://fcm.googleapis.com/fcm/send/abc123")
                String endpoint,
        @Schema(description = "Instante UTC de registro de la suscripción") Instant createdAt) {}
