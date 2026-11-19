package com.eyelesscode.task_by_task.shared.error;

import java.util.List;
import java.util.Objects;

public class FieldValidationException extends RuntimeException {

    private final List<ValidationErrorDetail> invalidParams;

    public FieldValidationException(List<ValidationErrorDetail> invalidParams) {
        super("Uno o más campos contienen errores de validación");
        Objects.requireNonNull(invalidParams, "invalidParams must not be null");
        if (invalidParams.isEmpty()) {
            throw new IllegalArgumentException("invalidParams must not be empty");
        }
        this.invalidParams = List.copyOf(invalidParams);
    }

    public List<ValidationErrorDetail> getInvalidParams() {
        return invalidParams;
    }
}
