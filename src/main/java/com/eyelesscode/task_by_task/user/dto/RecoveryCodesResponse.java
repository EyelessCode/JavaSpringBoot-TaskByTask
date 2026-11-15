package com.eyelesscode.task_by_task.user.dto;

import java.util.List;

public record RecoveryCodesResponse(List<String> recoveryCodes, String recoveryCodesNotice) {

    public static final String DEFAULT_RECOVERY_CODES_NOTICE =
            "Guarde estos 8 códigos de recuperación en un lugar seguro. Se muestran una única vez y"
                    + " cada código es de un solo uso.";

    public RecoveryCodesResponse(List<String> recoveryCodes) {
        this(recoveryCodes, DEFAULT_RECOVERY_CODES_NOTICE);
    }
}
