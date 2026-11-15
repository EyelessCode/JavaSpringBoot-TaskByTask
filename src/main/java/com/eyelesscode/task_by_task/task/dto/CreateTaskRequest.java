package com.eyelesscode.task_by_task.task.dto;

import com.eyelesscode.task_by_task.tag.TagNameRules;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Request payload for creating a task inside a group. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateTaskRequest(
        @NotBlank(message = "El título de la tarea es obligatorio") @Size(
                        min = 2,
                        max = 100,
                        message = "El título de la tarea debe tener entre 2 y 100 caracteres")
                String title,
        @Size(max = 750, message = "La descripción de la tarea no puede superar los 750 caracteres") String description,
        @JsonDeserialize(using = StrictLocalDateDeserializer.class) LocalDate dueDate,
        @JsonDeserialize(using = StrictLocalTimeDeserializer.class) LocalTime dueTime,
        List<
                        @NotBlank(message = TagNameRules.REQUIRED_MESSAGE) @Size(max = TagNameRules.MAX_LENGTH, message = TagNameRules.LENGTH_MESSAGE) @Pattern(
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
