package com.eyelesscode.task_by_task.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(
        description =
                "Solicitud opcional al cerrar sesión para desregistrar Web Push del dispositivo")
public record LogoutRequest(
        @Schema(
                        description = "URL del endpoint Web Push del dispositivo actual a eliminar",
                        example = "https://fcm.googleapis.com/fcm/send/abc123",
                        nullable = true)
                String endpoint) {

    public LogoutRequest {
        if (endpoint != null) {
            endpoint = endpoint.trim();
        }
    }
}
