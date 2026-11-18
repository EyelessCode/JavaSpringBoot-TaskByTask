package com.eyelesscode.task_by_task.notification.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.net.URI;

/** Validator for {@link ValidPushEndpoint}. */
public class ValidPushEndpointValidator implements ConstraintValidator<ValidPushEndpoint, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            return scheme != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && host != null
                    && !host.isBlank();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
