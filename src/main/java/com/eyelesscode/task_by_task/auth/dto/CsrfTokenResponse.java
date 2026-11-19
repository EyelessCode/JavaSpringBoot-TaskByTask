package com.eyelesscode.task_by_task.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Response payload for {@code GET /api/v1/auth/csrf} bootstrap endpoint (HRD-REQ-05). */
@Schema(description = "Token anti-CSRF y nombres de cabecera/parámetro esperados")
public record CsrfTokenResponse(
        @Schema(
                        description = "Valor del token anti-CSRF",
                        example = "66711b14-1176-4fe7-9a1f-2ac07dc06ac7")
                String token,
        @Schema(
                        description = "Nombre de la cabecera HTTP donde enviar el token",
                        example = "X-XSRF-TOKEN")
                String headerName,
        @Schema(description = "Nombre del parámetro de formulario alternativo", example = "_csrf")
                String parameterName) {}
