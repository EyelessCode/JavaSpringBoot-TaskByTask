package com.eyelesscode.task_by_task.task.dto;

import com.eyelesscode.task_by_task.tag.dto.TagResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** API response representation of a task with its resolved overdue status and ordered tags. */
public record TaskResponse(
        Long id,
        Long groupId,
        Long assignedUserId,
        String title,
        String description,
        boolean completed,
        Instant completedAt,
        boolean overdue,
        LocalDate dueDate,
        LocalTime dueTime,
        List<TagResponse> tags,
        Instant createdAt,
        Instant updatedAt) {

    public TaskResponse {
        tags = tags != null ? List.copyOf(tags) : List.of();
    }
}
