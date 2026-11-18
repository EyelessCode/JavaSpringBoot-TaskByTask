package com.eyelesscode.task_by_task.notification;

import com.eyelesscode.task_by_task.notification.dto.PushSubscriptionResponse;
import com.eyelesscode.task_by_task.notification.dto.RegisterPushSubscriptionRequest;
import com.eyelesscode.task_by_task.notification.dto.VapidPublicKeyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST controller exposing Web Push subscription and notification endpoints. */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "Gestión de suscripciones Web Push y notificaciones")
public class NotificationController {

    private final PushSubscriptionService pushSubscriptionService;

    public NotificationController(PushSubscriptionService pushSubscriptionService) {
        this.pushSubscriptionService = pushSubscriptionService;
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
}
