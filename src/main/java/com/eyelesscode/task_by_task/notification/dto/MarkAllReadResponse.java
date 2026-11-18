package com.eyelesscode.task_by_task.notification.dto;

/** Response payload for {@code PATCH /api/v1/notifications/read-all}. */
public record MarkAllReadResponse(long updatedCount) {}
