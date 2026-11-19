package com.eyelesscode.task_by_task.task.dto;

import com.eyelesscode.task_by_task.tag.dto.TagResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** API response representation of a task with its resolved overdue status and ordered tags. */
@Schema(description = "Representación completa de una tarea con estado de vencimiento y etiquetas")
public record TaskResponse(
        @Schema(description = "Identificador único de la tarea", example = "42") Long id,
        @Schema(description = "Identificador del grupo al que pertenece la tarea", example = "1")
                Long groupId,
        @Schema(
                        description =
                                "Identificador del usuario asignado (reservado para extensibilidad,"
                                        + " nulo en MVP)",
                        nullable = true)
                Long assignedUserId,
        @Schema(description = "Título de la tarea", example = "Entregar reporte mensual")
                String title,
        @Schema(description = "Descripción de la tarea", nullable = true) String description,
        @Schema(description = "Indica si la tarea está completada") boolean completed,
        @Schema(description = "Instante UTC en que se completó la tarea", nullable = true)
                Instant completedAt,
        @Schema(
                        description =
                                "Indica si la tarea está vencida según la zona horaria del usuario"
                                        + " (tareas solo-fecha vencen a las 23:59:59.999)")
                boolean overdue,
        @Schema(
                        description = "Fecha límite en formato ISO-8601 (yyyy-MM-dd)",
                        format = "date",
                        nullable = true)
                LocalDate dueDate,
        @Schema(
                        description = "Hora límite en formato ISO-8601 (HH:mm[:ss])",
                        format = "time",
                        nullable = true)
                LocalTime dueTime,
        @Schema(description = "Etiquetas asociadas a la tarea ordenadas alfabéticamente")
                List<TagResponse> tags,
        @Schema(description = "Instante UTC de creación de la tarea") Instant createdAt,
        @Schema(description = "Instante UTC de última actualización de la tarea")
                Instant updatedAt) {

    public TaskResponse {
        tags = tags != null ? List.copyOf(tags) : List.of();
    }
}
