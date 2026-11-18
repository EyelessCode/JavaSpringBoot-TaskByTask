package com.eyelesscode.task_by_task.notification.dto;

/** Response payload for {@code GET /api/v1/notifications/unread-count}. */
public record UnreadCountResponse(long unreadCount) {}
