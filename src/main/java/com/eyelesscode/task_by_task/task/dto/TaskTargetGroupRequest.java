package com.eyelesscode.task_by_task.task.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

/** Request payload specifying the target group for moving or copying a task. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskTargetGroupRequest(
        @NotNull(message = "El grupo destino es obligatorio") Long targetGroupId) {}
