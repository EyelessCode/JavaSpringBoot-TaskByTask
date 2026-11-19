package com.eyelesscode.task_by_task.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** API response representation of an in-app notification. */
@Schema(description = "Notificación almacenada en el centro de notificaciones del usuario")
public record NotificationResponse(
        @Schema(description = "Identificador único de la notificación", example = "12") Long id,
        @Schema(description = "Tipo de evento de notificación", example = "TASK_DUE")
                String eventType,
        @Schema(
                        description = "Contenido textual en español de la notificación",
                        example = "La tarea 'Entregar reporte' vence hoy")
                String content,
        @Schema(description = "Indica si la notificación ya fue leída") boolean read,
        @Schema(description = "Instante UTC de creación de la notificación") Instant createdAt) {}
