package com.eyelesscode.task_by_task.auth.dto;

/** Response payload for {@code GET /api/v1/auth/csrf} bootstrap endpoint (HRD-REQ-05). */
public record CsrfTokenResponse(String token, String headerName, String parameterName) {}
