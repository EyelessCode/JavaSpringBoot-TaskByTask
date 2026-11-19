package com.eyelesscode.task_by_task.notification;

import com.eyelesscode.task_by_task.notification.dto.PushSubscriptionResponse;
import java.time.Instant;
import java.util.Objects;

/**
 * Explicit manual mapper from {@link PushSubscription} or JDBC row to {@link
 * PushSubscriptionResponse}.
 */
public final class PushSubscriptionMapper {

    private PushSubscriptionMapper() {}

    public static PushSubscriptionResponse toResponse(PushSubscription subscription) {
        Objects.requireNonNull(subscription, "subscription must not be null");
        return new PushSubscriptionResponse(
                subscription.getId(), subscription.getEndpoint(), subscription.getCreatedAt());
    }

    public static PushSubscriptionResponse toResponse(Long id, String endpoint, Instant createdAt) {
        return new PushSubscriptionResponse(id, endpoint, createdAt);
    }
}
