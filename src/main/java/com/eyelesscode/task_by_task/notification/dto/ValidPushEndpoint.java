package com.eyelesscode.task_by_task.notification.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a Web Push subscription endpoint is an absolute {@code http}/{@code https} URL.
 */
@Documented
@Constraint(validatedBy = ValidPushEndpointValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPushEndpoint {

    String message() default "El endpoint debe ser una URL absoluta http o https válida";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
