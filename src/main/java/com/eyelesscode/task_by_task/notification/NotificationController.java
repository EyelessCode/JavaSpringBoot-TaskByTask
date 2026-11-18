package com.eyelesscode.task_by_task.notification;

import com.eyelesscode.task_by_task.notification.dto.MarkAllReadResponse;
import com.eyelesscode.task_by_task.notification.dto.NotificationResponse;
import com.eyelesscode.task_by_task.notification.dto.PushSubscriptionResponse;
import com.eyelesscode.task_by_task.notification.dto.RegisterPushSubscriptionRequest;
import com.eyelesscode.task_by_task.notification.dto.UnreadCountResponse;
import com.eyelesscode.task_by_task.notification.dto.VapidPublicKeyResponse;
import com.eyelesscode.task_by_task.shared.pagination.KeysetPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST controller exposing Web Push subscription and Notification Center endpoints. */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "Gestión de suscripciones Web Push y notificaciones")
public class NotificationController {

    private final PushSubscriptionService pushSubscriptionService;
    private final NotificationService notificationService;

    public NotificationController(
            PushSubscriptionService pushSubscriptionService,
            NotificationService notificationService) {
        this.pushSubscriptionService = pushSubscriptionService;
        this.notificationService = notificationService;
    }

    @GetMapping("/push/vapid-public-key")
    @Operation(summary = "Obtener la clave pública VAPID del servidor")
    public ResponseEntity<VapidPublicKeyResponse> getVapidPublicKey() {
        return ResponseEntity.ok(pushSubscriptionService.getVapidPublicKey());
    }

    @PostMapping("/push/subscriptions")
    @Operation(summary = "Registrar o actualizar una suscripción Web Push del dispositivo")
    public ResponseEntity<PushSubscriptionResponse> registerSubscription(
            @Valid @RequestBody RegisterPushSubscriptionRequest request) {
        PushSubscriptionService.RegistrationResult result =
                pushSubscriptionService.registerOrRebind(request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }

    @DeleteMapping("/push/subscriptions/{subscriptionId}")
    @Operation(summary = "Eliminar una suscripción Web Push por identificador")
    public ResponseEntity<Void> deleteSubscriptionById(@PathVariable Long subscriptionId) {
        pushSubscriptionService.deleteById(subscriptionId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/push/subscriptions")
    @Operation(summary = "Eliminar una suscripción Web Push del usuario actual por endpoint")
    public ResponseEntity<Void> deleteSubscriptionByEndpoint(
            @RequestParam(name = "endpoint", required = false) String endpoint) {
        pushSubscriptionService.deleteByEndpoint(endpoint);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @Operation(summary = "Listar notificaciones del usuario con paginación Keyset")
    public ResponseEntity<KeysetPage<NotificationResponse>> listNotifications(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return ResponseEntity.ok(notificationService.listNotifications(cursor, limit, unreadOnly));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Obtener el conteo de notificaciones no leídas")
    public ResponseEntity<UnreadCountResponse> getUnreadCount() {
        return ResponseEntity.ok(notificationService.getUnreadCount());
    }

    @PatchMapping("/{notificationId}/read")
    @Operation(summary = "Marcar una notificación individual como leída")
    public ResponseEntity<NotificationResponse> markAsRead(@PathVariable Long notificationId) {
        return ResponseEntity.ok(notificationService.markAsRead(notificationId));
    }

    @PatchMapping("/read-all")
    @Operation(summary = "Marcar todas las notificaciones del usuario como leídas")
    public ResponseEntity<MarkAllReadResponse> markAllAsRead() {
        return ResponseEntity.ok(notificationService.markAllAsRead());
    }

    @DeleteMapping("/{notificationId}")
    @Operation(summary = "Eliminar una notificación del usuario")
    public ResponseEntity<Void> deleteNotification(@PathVariable Long notificationId) {
        notificationService.deleteNotification(notificationId);
        return ResponseEntity.noContent().build();
    }
}
