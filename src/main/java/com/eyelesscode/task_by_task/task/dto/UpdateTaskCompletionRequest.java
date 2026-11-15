package com.eyelesscode.task_by_task.task.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

/** Request payload for toggling a task's completion status. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateTaskCompletionRequest(
        @NotNull(message = "El estado de completado es obligatorio") Boolean completed) {}
