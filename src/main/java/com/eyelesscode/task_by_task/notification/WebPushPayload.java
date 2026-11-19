package com.eyelesscode.task_by_task.notification;

import java.time.Instant;

/** JSON payload encrypted and delivered to browsers via Web Push (RFC 8291). */
public record WebPushPayload(
        Long notificationId,
        String eventType,
        String title,
        String body,
        Long taskId,
        Long groupId,
        Instant createdAt) {}
