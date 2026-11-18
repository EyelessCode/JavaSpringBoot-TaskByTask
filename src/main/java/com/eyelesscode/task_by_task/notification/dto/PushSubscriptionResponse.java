package com.eyelesscode.task_by_task.notification.dto;

import java.time.Instant;

/** API response representation of a registered push subscription (omitting cryptographic keys). */
public record PushSubscriptionResponse(Long id, String endpoint, Instant createdAt) {}
