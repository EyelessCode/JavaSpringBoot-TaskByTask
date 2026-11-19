package com.eyelesscode.task_by_task.auth.dto;

import com.eyelesscode.task_by_task.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(
        description =
                "Resultado del registro: perfil creado y los 8 códigos de recuperación en texto"
                        + " plano (mostrados una única vez)")
public record RegisterResponse(
        @Schema(description = "Perfil del usuario recién creado") UserResponse user,
        @Schema(description = "Lista de 8 códigos de recuperación de un solo uso")
                List<String> recoveryCodes,
        @Schema(description = "Aviso de seguridad sobre la conservación de los códigos")
                String recoveryCodesNotice) {

    public static final String DEFAULT_RECOVERY_CODES_NOTICE =
            "Guarde estos 8 códigos de recuperación en un lugar seguro. Se muestran una única vez y"
                    + " cada código es de un solo uso.";

    public RegisterResponse(UserResponse user, List<String> recoveryCodes) {
        this(user, recoveryCodes, DEFAULT_RECOVERY_CODES_NOTICE);
    }
}
