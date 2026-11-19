package com.eyelesscode.task_by_task.task;

import com.eyelesscode.task_by_task.shared.time.DateTimeProvider;
import com.eyelesscode.task_by_task.tag.dto.TagResponse;
import com.eyelesscode.task_by_task.task.dto.TaskResponse;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/** Explicit manual mapper from {@link Task} to {@link TaskResponse}. */
public final class TaskMapper {

    private TaskMapper() {}

    public static TaskResponse toResponse(
            Task task,
            List<TagResponse> tags,
            DateTimeProvider dateTimeProvider,
            ZoneId userZone,
            Instant now) {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(dateTimeProvider, "dateTimeProvider must not be null");
        Objects.requireNonNull(userZone, "userZone must not be null");
        Objects.requireNonNull(now, "now must not be null");

        boolean overdue =
                !task.isCompleted()
                        && dateTimeProvider.isOverdue(
                                task.getDueDate(), task.getDueTime(), userZone, now);
        Long assignedUserId =
                task.getAssignedUser() != null ? task.getAssignedUser().getId() : null;

        return new TaskResponse(
                task.getId(),
                task.getGroup().getId(),
                assignedUserId,
                task.getTitle(),
                task.getDescription(),
                task.isCompleted(),
                task.getCompletedAt(),
                overdue,
                task.getDueDate(),
                task.getDueTime(),
                tags,
                task.getCreatedAt(),
                task.getUpdatedAt());
    }
}
