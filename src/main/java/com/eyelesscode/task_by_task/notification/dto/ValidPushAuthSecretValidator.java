package com.eyelesscode.task_by_task.notification.dto;

import com.eyelesscode.task_by_task.notification.WebPushCryptoUtils;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Validator for {@link ValidPushAuthSecret}. */
public class ValidPushAuthSecretValidator
        implements ConstraintValidator<ValidPushAuthSecret, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        try {
            WebPushCryptoUtils.validateAuthSecret(value.trim());
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
