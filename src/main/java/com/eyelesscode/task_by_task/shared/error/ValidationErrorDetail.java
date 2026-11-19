package com.eyelesscode.task_by_task.shared.error;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Detalle individual de un error de validación de campo")
public record ValidationErrorDetail(
        @Schema(description = "Nombre del campo o parámetro inválido", example = "title")
                String name,
        @Schema(
                        description = "Motivo del error de validación en español",
                        example = "El título debe tener entre 2 y 100 caracteres")
                String reason) {

    public String field() {
        return name;
    }

    public String message() {
        return reason;
    }
}
