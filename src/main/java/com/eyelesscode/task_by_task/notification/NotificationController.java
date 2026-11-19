package com.eyelesscode.task_by_task.notification;

import com.eyelesscode.task_by_task.notification.dto.MarkAllReadResponse;
import com.eyelesscode.task_by_task.notification.dto.NotificationResponse;
import com.eyelesscode.task_by_task.notification.dto.PushSubscriptionResponse;
import com.eyelesscode.task_by_task.notification.dto.RegisterPushSubscriptionRequest;
import com.eyelesscode.task_by_task.notification.dto.UnreadCountResponse;
import com.eyelesscode.task_by_task.notification.dto.VapidPublicKeyResponse;
import com.eyelesscode.task_by_task.shared.config.OpenApiConfig;
import com.eyelesscode.task_by_task.shared.pagination.KeysetPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
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
@Tag(
        name = "Notifications",
        description = "Gestión de suscripciones Web Push y centro de notificaciones")
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
    @SecurityRequirements({@SecurityRequirement(name = OpenApiConfig.SECURITY_SESSION_COOKIE)})
    @Operation(
            summary = "Obtener la clave pública VAPID del servidor",
            description =
                    "Indica si Web Push está habilitado (`enabled`) y devuelve la clave pública"
                            + " VAPID Base64URL para registrar el Service Worker del navegador.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Estado y clave pública VAPID"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado")
    })
    public ResponseEntity<VapidPublicKeyResponse> getVapidPublicKey() {
        return ResponseEntity.ok(pushSubscriptionService.getVapidPublicKey());
    }

    @PostMapping("/push/subscriptions")
    @Operation(
            summary = "Registrar o re-vincular una suscripción Web Push del dispositivo",
            description =
                    "Crea la suscripción (HTTP 201) o actualiza/re-vincula un endpoint existente al"
                            + " usuario autenticado (HTTP 200).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Suscripción existente actualizada"),
        @ApiResponse(responseCode = "201", description = "Suscripción creada correctamente"),
        @ApiResponse(responseCode = "400", description = "Error de validación o URL inválida"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido"),
        @ApiResponse(responseCode = "415", description = "Tipo de medio no soportado")
    })
    public ResponseEntity<PushSubscriptionResponse> registerSubscription(
            @Valid @RequestBody RegisterPushSubscriptionRequest request) {
        PushSubscriptionService.RegistrationResult result =
                pushSubscriptionService.registerOrRebind(request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }

    @DeleteMapping("/push/subscriptions/{subscriptionId}")
    @Operation(summary = "Eliminar una suscripción Web Push por identificador")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Suscripción eliminada correctamente"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(
                responseCode = "403",
                description =
                        "Token CSRF ausente/inválido o la suscripción no pertenece al usuario"),
        @ApiResponse(responseCode = "404", description = "Suscripción no encontrada")
    })
    public ResponseEntity<Void> deleteSubscriptionById(
            @Parameter(description = "Identificador de la suscripción Web Push", example = "5")
                    @PathVariable
                    Long subscriptionId) {
        pushSubscriptionService.deleteById(subscriptionId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/push/subscriptions")
    @Operation(summary = "Eliminar una suscripción Web Push del usuario actual por endpoint")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Suscripción eliminada (idempotente)"),
        @ApiResponse(responseCode = "400", description = "Parámetro endpoint ausente o vacío"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido")
    })
    public ResponseEntity<Void> deleteSubscriptionByEndpoint(
            @Parameter(
                            description = "URL del endpoint Web Push del navegador a desregistrar",
                            required = true)
                    @RequestParam(name = "endpoint", required = false)
                    String endpoint) {
        pushSubscriptionService.deleteByEndpoint(endpoint);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @SecurityRequirements({@SecurityRequirement(name = OpenApiConfig.SECURITY_SESSION_COOKIE)})
    @Operation(
            summary = "Listar notificaciones del usuario con paginación Keyset",
            description = "Ordenadas por fecha de creación descendente y con retención de 30 días.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado paginado de notificaciones"),
        @ApiResponse(responseCode = "400", description = "Cursor o límite inválido"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado")
    })
    public ResponseEntity<KeysetPage<NotificationResponse>> listNotifications(
            @Parameter(description = "Cursor opaco Base64URL de la página anterior")
                    @RequestParam(required = false)
                    String cursor,
            @Parameter(description = "Cantidad máxima de elementos por página (1 a 50)")
                    @RequestParam(defaultValue = "20")
                    int limit,
            @Parameter(description = "Si es true devuelve únicamente notificaciones no leídas")
                    @RequestParam(defaultValue = "false")
                    boolean unreadOnly) {
        return ResponseEntity.ok(notificationService.listNotifications(cursor, limit, unreadOnly));
    }

    @GetMapping("/unread-count")
    @SecurityRequirements({@SecurityRequirement(name = OpenApiConfig.SECURITY_SESSION_COOKIE)})
    @Operation(summary = "Obtener el conteo de notificaciones no leídas")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Conteo de notificaciones no leídas"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado")
    })
    public ResponseEntity<UnreadCountResponse> getUnreadCount() {
        return ResponseEntity.ok(notificationService.getUnreadCount());
    }

    @PatchMapping("/{notificationId}/read")
    @Operation(summary = "Marcar una notificación individual como leída")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Notificación marcada como leída"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(
                responseCode = "403",
                description =
                        "Token CSRF ausente/inválido o la notificación no pertenece al usuario"),
        @ApiResponse(responseCode = "404", description = "Notificación no encontrada")
    })
    public ResponseEntity<NotificationResponse> markAsRead(
            @Parameter(description = "Identificador de la notificación", example = "12")
                    @PathVariable
                    Long notificationId) {
        return ResponseEntity.ok(notificationService.markAsRead(notificationId));
    }

    @PatchMapping("/read-all")
    @Operation(summary = "Marcar todas las notificaciones del usuario como leídas")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Notificaciones marcadas como leídas con conteo actualizado"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido")
    })
    public ResponseEntity<MarkAllReadResponse> markAllAsRead() {
        return ResponseEntity.ok(notificationService.markAllAsRead());
    }

    @DeleteMapping("/{notificationId}")
    @Operation(summary = "Eliminar una notificación del usuario")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Notificación eliminada correctamente"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(
                responseCode = "403",
                description =
                        "Token CSRF ausente/inválido o la notificación no pertenece al usuario"),
        @ApiResponse(responseCode = "404", description = "Notificación no encontrada")
    })
    public ResponseEntity<Void> deleteNotification(
            @Parameter(description = "Identificador de la notificación", example = "12")
                    @PathVariable
                    Long notificationId) {
        notificationService.deleteNotification(notificationId);
        return ResponseEntity.noContent().build();
    }
}
