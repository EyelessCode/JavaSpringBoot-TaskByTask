package com.eyelesscode.task_by_task.notification;

import com.eyelesscode.task_by_task.notification.dto.NotificationResponse;
import java.util.Objects;

/** Explicit manual mapper from {@link Notification} to {@link NotificationResponse}. */
public final class NotificationMapper {

    private NotificationMapper() {}

    public static NotificationResponse toResponse(Notification notification) {
        Objects.requireNonNull(notification, "notification must not be null");
        return new NotificationResponse(
                notification.getId(),
                notification.getEventType().name(),
                notification.getContent(),
                notification.isRead(),
                notification.getCreatedAt());
    }
}
