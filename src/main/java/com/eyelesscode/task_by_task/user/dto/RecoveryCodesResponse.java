package com.eyelesscode.task_by_task.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Nuevos 8 códigos de recuperación de un solo uso (mostrados una única vez)")
public record RecoveryCodesResponse(
        @Schema(description = "Lista de 8 códigos en texto plano") List<String> recoveryCodes,
        @Schema(description = "Aviso de seguridad sobre la conservación de los códigos")
                String recoveryCodesNotice) {

    public static final String DEFAULT_RECOVERY_CODES_NOTICE =
            "Guarde estos 8 códigos de recuperación en un lugar seguro. Se muestran una única vez y"
                    + " cada código es de un solo uso.";

    public RecoveryCodesResponse(List<String> recoveryCodes) {
        this(recoveryCodes, DEFAULT_RECOVERY_CODES_NOTICE);
    }
}
