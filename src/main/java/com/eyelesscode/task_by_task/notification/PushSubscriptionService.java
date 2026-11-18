package com.eyelesscode.task_by_task.notification;

import com.eyelesscode.task_by_task.notification.dto.PushSubscriptionResponse;
import com.eyelesscode.task_by_task.notification.dto.RegisterPushSubscriptionRequest;
import com.eyelesscode.task_by_task.notification.dto.VapidPublicKeyResponse;
import com.eyelesscode.task_by_task.shared.error.BadRequestException;
import com.eyelesscode.task_by_task.shared.error.ResourceNotFoundException;
import com.eyelesscode.task_by_task.shared.security.SecurityUtils;
import com.eyelesscode.task_by_task.shared.time.DateTimeProvider;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application service for VAPID public key exposure and Web Push subscription lifecycle. */
@Service
public class PushSubscriptionService {

    private static final String UPSERT_SUBSCRIPTION_SQL =
            """
            INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth, created_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (endpoint) DO UPDATE
            SET user_id = EXCLUDED.user_id,
                p256dh = EXCLUDED.p256dh,
                auth = EXCLUDED.auth,
                created_at = CASE
                    WHEN push_subscriptions.user_id = EXCLUDED.user_id THEN push_subscriptions.created_at
                    ELSE EXCLUDED.created_at
                END
            RETURNING id, endpoint, created_at, (xmax = 0) AS inserted
            """;

    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final VapidConfigurationState vapidConfigurationState;
    private final DateTimeProvider dateTimeProvider;
    private final JdbcTemplate jdbcTemplate;

    public PushSubscriptionService(
            PushSubscriptionRepository pushSubscriptionRepository,
            VapidConfigurationState vapidConfigurationState,
            DateTimeProvider dateTimeProvider,
            JdbcTemplate jdbcTemplate) {
        this.pushSubscriptionRepository = pushSubscriptionRepository;
        this.vapidConfigurationState = vapidConfigurationState;
        this.dateTimeProvider = dateTimeProvider;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record RegistrationResult(PushSubscriptionResponse response, boolean created) {}

    public VapidPublicKeyResponse getVapidPublicKey() {
        return new VapidPublicKeyResponse(
                vapidConfigurationState.isActive(), vapidConfigurationState.getPublicKey());
    }

    @Transactional
    public RegistrationResult registerOrRebind(RegisterPushSubscriptionRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        Instant now = dateTimeProvider.now().truncatedTo(ChronoUnit.MICROS);

        RegistrationResult result =
                jdbcTemplate.queryForObject(
                        UPSERT_SUBSCRIPTION_SQL,
                        (rs, rowNum) -> {
                            long id = rs.getLong("id");
                            String endpoint = rs.getString("endpoint");
                            Instant createdAt = rs.getTimestamp("created_at").toInstant();
                            boolean inserted = rs.getBoolean("inserted");
                            return new RegistrationResult(
                                    PushSubscriptionMapper.toResponse(id, endpoint, createdAt),
                                    inserted);
                        },
                        userId,
                        request.endpoint(),
                        request.keys().p256dh(),
                        request.keys().auth(),
                        Timestamp.from(now));

        return Objects.requireNonNull(result, "Upsert must return a RegistrationResult");
    }

    @Transactional
    public void deleteById(Long subscriptionId) {
        Long userId = SecurityUtils.getCurrentUserId();
        PushSubscription subscription =
                pushSubscriptionRepository
                        .findByIdWithUser(subscriptionId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "Suscripción push no encontrada"));
        if (!subscription.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("No tiene permisos para realizar esta acción");
        }
        pushSubscriptionRepository.delete(subscription);
        pushSubscriptionRepository.flush();
    }

    @Transactional
    public void deleteByEndpoint(String rawEndpoint) {
        Long userId = SecurityUtils.getCurrentUserId();
        String trimmed = rawEndpoint != null ? rawEndpoint.trim() : "";
        if (trimmed.isEmpty()) {
            throw new BadRequestException(
                    "El parámetro 'endpoint' es obligatorio y no puede estar vacío");
        }
        pushSubscriptionRepository.deleteByUserIdAndEndpoint(userId, trimmed);
    }
}
