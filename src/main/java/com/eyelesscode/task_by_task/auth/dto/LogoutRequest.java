package com.eyelesscode.task_by_task.auth.dto;

public record LogoutRequest(String endpoint) {

    public LogoutRequest {
        if (endpoint != null) {
            endpoint = endpoint.trim();
        }
    }
}
