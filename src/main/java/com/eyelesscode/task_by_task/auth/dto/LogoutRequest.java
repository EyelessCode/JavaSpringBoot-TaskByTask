package com.eyelesscode.task_by_task.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LogoutRequest(String endpoint) {

    public LogoutRequest {
        if (endpoint != null) {
            endpoint = endpoint.trim();
        }
    }
}
