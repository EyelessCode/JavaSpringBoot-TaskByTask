package com.eyelesscode.task_by_task.group.dto;

import java.time.Instant;

public record GroupResponse(
        Long id,
        String name,
        boolean isPersonal,
        Long creatorId,
        Instant createdAt,
        Instant updatedAt) {}
