package com.eyelesscode.task_by_task.notification;

import com.eyelesscode.task_by_task.notification.dto.MarkAllReadResponse;
import com.eyelesscode.task_by_task.notification.dto.NotificationResponse;
import com.eyelesscode.task_by_task.notification.dto.UnreadCountResponse;
import com.eyelesscode.task_by_task.shared.error.BadRequestException;
import com.eyelesscode.task_by_task.shared.error.ResourceNotFoundException;
import com.eyelesscode.task_by_task.shared.pagination.KeysetCursor;
import com.eyelesscode.task_by_task.shared.pagination.KeysetCursorCodec;
import com.eyelesscode.task_by_task.shared.pagination.KeysetPage;
import com.eyelesscode.task_by_task.shared.security.SecurityUtils;
import com.eyelesscode.task_by_task.shared.time.DateTimeProvider;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application service for the in-app Notification Center and 30-day retention cleanup. */
@Service
public class NotificationService {

    private static final int MIN_PAGE_LIMIT = 1;
    private static final int MAX_PAGE_LIMIT = 50;
    public static final long RETENTION_DAYS = 30L;

    private final NotificationRepository notificationRepository;
    private final KeysetCursorCodec keysetCursorCodec;
    private final DateTimeProvider dateTimeProvider;

    public NotificationService(
            NotificationRepository notificationRepository,
            KeysetCursorCodec keysetCursorCodec,
            DateTimeProvider dateTimeProvider) {
        this.notificationRepository = notificationRepository;
        this.keysetCursorCodec = keysetCursorCodec;
        this.dateTimeProvider = dateTimeProvider;
    }

    @Transactional(readOnly = true)
    public KeysetPage<NotificationResponse> listNotifications(
            String cursor, int limit, boolean unreadOnly) {
        if (limit < MIN_PAGE_LIMIT || limit > MAX_PAGE_LIMIT) {
            throw new BadRequestException("El límite de paginación debe estar entre 1 y 50");
        }

        Long userId = SecurityUtils.getCurrentUserId();
        KeysetCursor.NotificationCursor decoded =
                cursor != null ? keysetCursorCodec.decodeNotificationCursor(cursor) : null;
        Pageable pageable = PageRequest.of(0, limit + 1);

        List<Notification> rows =
                decoded == null
                        ? notificationRepository.findFirstPage(userId, unreadOnly, pageable)
                        : notificationRepository.findNextPage(
                                userId, unreadOnly, decoded.createdAt(), decoded.id(), pageable);

        List<NotificationResponse> responses =
                rows.stream().map(NotificationMapper::toResponse).toList();
        String nextCursor = null;
        if (responses.size() > limit) {
            NotificationResponse last = responses.get(limit - 1);
            nextCursor = keysetCursorCodec.encodeNotificationCursor(last.createdAt(), last.id());
        }

        return KeysetPage.of(responses, limit, nextCursor);
    }

    @Transactional(readOnly = true)
    public UnreadCountResponse getUnreadCount() {
        Long userId = SecurityUtils.getCurrentUserId();
        return new UnreadCountResponse(notificationRepository.countByUserIdAndReadFalse(userId));
    }

    @Transactional
    public NotificationResponse markAsRead(Long notificationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Notification notification = loadOwnedNotification(notificationId, userId);
        if (!notification.isRead()) {
            notification.setRead(true);
            notificationRepository.save(notification);
        }
        return NotificationMapper.toResponse(notification);
    }

    @Transactional
    public MarkAllReadResponse markAllAsRead() {
        Long userId = SecurityUtils.getCurrentUserId();
        int updated = notificationRepository.markAllAsReadByUserId(userId);
        return new MarkAllReadResponse(updated);
    }

    @Transactional
    public void deleteNotification(Long notificationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Notification notification = loadOwnedNotification(notificationId, userId);
        notificationRepository.delete(notification);
        notificationRepository.flush();
    }

    @Transactional
    public int purgeExpiredNotifications() {
        Instant cutoff =
                dateTimeProvider
                        .now()
                        .truncatedTo(ChronoUnit.MICROS)
                        .minus(RETENTION_DAYS, ChronoUnit.DAYS);
        return notificationRepository.deleteByCreatedAtBefore(cutoff);
    }

    private Notification loadOwnedNotification(Long notificationId, Long userId) {
        Notification notification =
                notificationRepository
                        .findByIdWithUser(notificationId)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("Notificación no encontrada"));
        if (!notification.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("No tiene permisos para realizar esta acción");
        }
        return notification;
    }
}
