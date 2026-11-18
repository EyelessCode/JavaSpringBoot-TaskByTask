package com.eyelesscode.task_by_task.notification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request payload for registering or updating a W3C Web Push subscription. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegisterPushSubscriptionRequest(
        @NotBlank(message = "El endpoint de suscripción es obligatorio") @Size(max = 2048, message = "El endpoint no puede superar los 2048 caracteres") @ValidPushEndpoint
                String endpoint,
        @NotNull(message = "Las claves de suscripción son obligatorias") @Valid PushSubscriptionKeys keys) {

    public RegisterPushSubscriptionRequest {
        endpoint = endpoint != null ? endpoint.trim() : null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PushSubscriptionKeys(
            @NotBlank(message = "La clave pública p256dh es obligatoria") @Size(
                            max = 255,
                            message = "La clave p256dh no puede superar los 255 caracteres")
                    @ValidP256PublicKey
                    String p256dh,
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
