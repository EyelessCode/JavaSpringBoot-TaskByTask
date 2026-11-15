package com.eyelesscode.task_by_task.auth.dto;

import com.eyelesscode.task_by_task.user.dto.UserResponse;
import java.util.List;

public record RegisterResponse(
        UserResponse user, List<String> recoveryCodes, String recoveryCodesNotice) {

    public static final String DEFAULT_RECOVERY_CODES_NOTICE =
            "Guarde estos 8 códigos de recuperación en un lugar seguro. Se muestran una única vez y"
                    + " cada código es de un solo uso.";

    public RegisterResponse(UserResponse user, List<String> recoveryCodes) {
        this(user, recoveryCodes, DEFAULT_RECOVERY_CODES_NOTICE);
    }
}
