package com.eyelesscode.task_by_task.user.dto;

import java.time.Instant;

public record UserResponse(
        Long id,
        String email,
        String username,
        String displayName,
        String timezone,
        String timeFormat,
        Instant createdAt,
        int remainingRecoveryCodes) {}
