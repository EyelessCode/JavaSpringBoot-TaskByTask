package com.eyelesscode.task_by_task.notification.dto;

import java.time.Instant;

/** API response representation of an in-app notification. */
public record NotificationResponse(
        Long id, String eventType, String content, boolean read, Instant createdAt) {}
