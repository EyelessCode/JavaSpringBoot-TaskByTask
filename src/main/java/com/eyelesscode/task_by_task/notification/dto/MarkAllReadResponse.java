package com.eyelesscode.task_by_task.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Response payload for {@code PATCH /api/v1/notifications/read-all}. */
@Schema(description = "Resultado de marcar todas las notificaciones como leídas")
public record MarkAllReadResponse(
        @Schema(
                        description = "Cantidad de notificaciones actualizadas a estado leído",
                        example = "4")
                long updatedCount) {}
