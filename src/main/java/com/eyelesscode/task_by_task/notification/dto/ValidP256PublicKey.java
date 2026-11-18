package com.eyelesscode.task_by_task.notification.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Validates that {@code keys.p256dh} is a valid 65-byte uncompressed P-256 point (RFC 8291). */
@Documented
@Constraint(validatedBy = ValidP256PublicKeyValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidP256PublicKey {

    String message() default
            "La clave pública p256dh debe ser un punto EC P-256 sin comprimir de 65 bytes válido en"
                    + " Base64/Base64URL";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
