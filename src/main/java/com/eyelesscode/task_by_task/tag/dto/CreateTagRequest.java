package com.eyelesscode.task_by_task.tag.dto;

import com.eyelesscode.task_by_task.tag.TagNameRules;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Solicitud para crear o reutilizar una etiqueta dentro de un grupo")
public record CreateTagRequest(
        @Schema(
                        description =
                                "Nombre de la etiqueta (máx. 25 caracteres, sin comas ni punto y"
                                        + " coma)",
                        example = "urgente")
                @NotBlank(message = TagNameRules.REQUIRED_MESSAGE) @Size(max = TagNameRules.MAX_LENGTH, message = TagNameRules.LENGTH_MESSAGE) @Pattern(
                        regexp = TagNameRules.NO_DELIMITERS_REGEX,
                        message = TagNameRules.DELIMITERS_MESSAGE)
                String name) {

    public CreateTagRequest {
        if (name != null) {
            name = name.trim();
        }
    }
}
