package com.eyelesscode.task_by_task.task.dto;

import com.eyelesscode.task_by_task.shared.error.FieldValidationException;
import com.eyelesscode.task_by_task.shared.error.ValidationErrorDetail;
import com.eyelesscode.task_by_task.tag.TagNameRules;
import java.util.ArrayList;
import java.util.List;

/** Validates only the fields explicitly present in an {@link UpdateTaskRequest}. */
public final class TaskPatchValidator {

    private static final int TITLE_MIN_LENGTH = 2;
    private static final int TITLE_MAX_LENGTH = 100;
    private static final int DESCRIPTION_MAX_LENGTH = 750;

    private TaskPatchValidator() {}

    public static void validate(UpdateTaskRequest request) {
        List<ValidationErrorDetail> errors = new ArrayList<>();

        if (request.title().present()) {
            String title = request.title().value();
            if (title == null || title.trim().isEmpty()) {
                errors.add(
                        new ValidationErrorDetail("title", "El título de la tarea es obligatorio"));
            } else {
                String trimmed = title.trim();
                if (trimmed.length() < TITLE_MIN_LENGTH || trimmed.length() > TITLE_MAX_LENGTH) {
                    errors.add(
                            new ValidationErrorDetail(
                                    "title",
                                    "El título de la tarea debe tener entre 2 y 100 caracteres"));
                }
            }
        }

        if (request.description().present() && request.description().value() != null) {
            if (request.description().value().length() > DESCRIPTION_MAX_LENGTH) {
                errors.add(
                        new ValidationErrorDetail(
                                "description",
                                "La descripción de la tarea no puede superar los 750 caracteres"));
            }
        }

        if (request.tags().present() && request.tags().value() != null) {
            TagNameRules.validateTagList(request.tags().value(), errors);
        }

        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
    }
}
