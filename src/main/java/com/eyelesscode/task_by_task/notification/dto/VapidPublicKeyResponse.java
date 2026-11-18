package com.eyelesscode.task_by_task.notification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Response payload for {@code GET /api/v1/notifications/push/vapid-public-key}. */
public record VapidPublicKeyResponse(
        boolean enabled, @JsonInclude(JsonInclude.Include.ALWAYS) String publicKey) {}
