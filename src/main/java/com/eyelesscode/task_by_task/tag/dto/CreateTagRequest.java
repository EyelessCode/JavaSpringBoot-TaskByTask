package com.eyelesscode.task_by_task.tag.dto;

import com.eyelesscode.task_by_task.tag.TagNameRules;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateTagRequest(
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
