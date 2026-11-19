package com.eyelesscode.task_by_task.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Response payload for {@code GET /api/v1/notifications/unread-count}. */
@Schema(description = "Conteo actual de notificaciones no leídas del usuario")
public record UnreadCountResponse(
        @Schema(description = "Cantidad de notificaciones sin leer", example = "3")
                long unreadCount) {}
