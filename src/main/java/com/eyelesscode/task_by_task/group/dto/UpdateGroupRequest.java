package com.eyelesscode.task_by_task.group.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateGroupRequest(
        @NotBlank(message = "El nombre del grupo es obligatorio") @Size(
                        min = 3,
                        max = 30,
                        message = "El nombre del grupo debe tener entre 3 y 30 caracteres")
                String name) {

    public UpdateGroupRequest {
        if (name != null) {
            name = name.trim();
        }
    }
}
