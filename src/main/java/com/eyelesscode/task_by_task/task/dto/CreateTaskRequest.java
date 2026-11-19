package com.eyelesscode.task_by_task.task.dto;

import com.eyelesscode.task_by_task.tag.TagNameRules;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Request payload for creating a task inside a group. */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Solicitud para crear una nueva tarea en un grupo")
public record CreateTaskRequest(
        @Schema(
                        description = "Título de la tarea (2 a 100 caracteres)",
                        example = "Entregar reporte mensual")
                @NotBlank(message = "El título de la tarea es obligatorio") @Size(
                        min = 2,
                        max = 100,
                        message = "El título de la tarea debe tener entre 2 y 100 caracteres")
                String title,
        @Schema(
                        description = "Descripción opcional de la tarea (máx. 750 caracteres)",
                        nullable = true)
                @Size(
                        max = 750,
                        message = "La descripción de la tarea no puede superar los 750 caracteres")
                String description,
        @Schema(
                        description = "Fecha límite ISO-8601 opcional (yyyy-MM-dd)",
                        format = "date",
                        example = "2026-12-01",
                        nullable = true)
                @JsonDeserialize(using = StrictLocalDateDeserializer.class)
                LocalDate dueDate,
        @Schema(
                        description =
                                "Hora límite ISO-8601 opcional (HH:mm[:ss]; requiere dueDate)",
                        format = "time",
                        example = "14:30:00",
                        nullable = true)
                @JsonDeserialize(using = StrictLocalTimeDeserializer.class)
                LocalTime dueTime,
        @Schema(
                        description =
                                "Lista opcional de nombres de etiquetas (máx. 25 caracteres c/u)",
                        nullable = true)
                List<
                                @NotBlank(message = TagNameRules.REQUIRED_MESSAGE) @Size(
                                        max = TagNameRules.MAX_LENGTH,
                                        message = TagNameRules.LENGTH_MESSAGE)
                                @Pattern(
                                        regexp = TagNameRules.NO_DELIMITERS_REGEX,
                                        message = TagNameRules.DELIMITERS_MESSAGE)
                                String>
                        tags) {

    public CreateTaskRequest {
        if (title != null) {
            title = title.trim();
        }
        if (description != null) {
            String trimmed = description.trim();
            description = trimmed.isEmpty() ? null : trimmed;
        }
        if (tags != null) {
            tags = tags.stream().map(t -> t != null ? t.trim() : null).toList();
        }
    }
}
