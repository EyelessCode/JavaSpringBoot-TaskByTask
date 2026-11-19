package com.eyelesscode.task_by_task.notification.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Validates that {@code keys.auth} is a valid 16-byte authentication secret (RFC 8291). */
@Documented
@Constraint(validatedBy = ValidPushAuthSecretValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPushAuthSecret {

    String message() default
            "El secreto auth debe ser un valor de 16 bytes válido en Base64/Base64URL";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
