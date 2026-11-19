package com.eyelesscode.task_by_task.notification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request payload for registering or updating a W3C Web Push subscription. */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Solicitud para registrar o actualizar una suscripción W3C Web Push")
public record RegisterPushSubscriptionRequest(
        @Schema(
                        description =
                                "URL HTTPS del servicio Push del navegador (máx. 2048 caracteres)",
                        example = "https://fcm.googleapis.com/fcm/send/abc123")
                @NotBlank(message = "El endpoint de suscripción es obligatorio") @Size(max = 2048, message = "El endpoint no puede superar los 2048 caracteres") @ValidPushEndpoint
                String endpoint,
        @Schema(description = "Claves criptográficas de suscripción Web Push (p256dh y auth)")
                @NotNull(message = "Las claves de suscripción son obligatorias") @Valid PushSubscriptionKeys keys) {

    public RegisterPushSubscriptionRequest {
        endpoint = endpoint != null ? endpoint.trim() : null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Claves criptográficas del cliente Web Push")
    public record PushSubscriptionKeys(
            @Schema(
                            description =
                                    "Clave pública P-256 del cliente en Base64URL (65 bytes sin"
                                            + " comprimir)")
                    @NotBlank(message = "La clave pública p256dh es obligatoria") @Size(
                            max = 255,
                            message = "La clave p256dh no puede superar los 255 caracteres")
                    @ValidP256PublicKey
                    String p256dh,
            @Schema(description = "Secreto de autenticación del cliente en Base64URL (16 bytes)")
                    @NotBlank(message = "El secreto de autenticación auth es obligatorio") @Size(
                            max = 255,
                            message = "El secreto auth no puede superar los 255 caracteres")
                    @ValidPushAuthSecret
                    String auth) {

        public PushSubscriptionKeys {
            p256dh = p256dh != null ? p256dh.trim() : null;
            auth = auth != null ? auth.trim() : null;
        }
    }
}
