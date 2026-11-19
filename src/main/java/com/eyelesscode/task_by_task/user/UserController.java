package com.eyelesscode.task_by_task.user;

import com.eyelesscode.task_by_task.auth.dto.MessageResponse;
import com.eyelesscode.task_by_task.shared.config.OpenApiConfig;
import com.eyelesscode.task_by_task.user.dto.ChangePasswordRequest;
import com.eyelesscode.task_by_task.user.dto.DeleteAccountRequest;
import com.eyelesscode.task_by_task.user.dto.RecoveryCodesResponse;
import com.eyelesscode.task_by_task.user.dto.RegenerateRecoveryCodesRequest;
import com.eyelesscode.task_by_task.user.dto.UpdateProfileRequest;
import com.eyelesscode.task_by_task.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
@Tag(name = "Users", description = "Gestión del perfil, contraseña, códigos y cuenta del usuario")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @SecurityRequirements({@SecurityRequirement(name = OpenApiConfig.SECURITY_SESSION_COOKIE)})
    @Operation(summary = "Consultar el perfil del usuario autenticado")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Perfil obtenido correctamente"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado")
    })
    public ResponseEntity<UserResponse> getProfile() {
        return ResponseEntity.ok(userService.getProfile());
    }

    @PatchMapping
    @Operation(
            summary = "Actualizar el perfil del usuario autenticado",
            description =
                    "Permite modificar nombre para mostrar, nombre de usuario, correo, zona"
                            + " horaria IANA y preferencia de formato de hora (24H/12H).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Perfil actualizado correctamente"),
        @ApiResponse(responseCode = "400", description = "Error de validación o valor inválido"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido"),
        @ApiResponse(
                responseCode = "409",
                description = "Correo electrónico o nombre de usuario ya registrado"),
        @ApiResponse(responseCode = "415", description = "Tipo de medio no soportado")
    })
    public ResponseEntity<UserResponse> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(userService.updateProfile(request));
    }

    @PostMapping("/password")
    @Operation(
            summary = "Cambiar la contraseña del usuario autenticado",
            description =
                    "Valida la contraseña actual, actualiza el hash e invalida las demás sesiones"
                            + " activas conservando la sesión actual.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Contraseña actualizada correctamente"),
        @ApiResponse(responseCode = "400", description = "Error de validación"),
        @ApiResponse(
                responseCode = "401",
                description = "Usuario no autenticado o contraseña actual incorrecta"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido"),
        @ApiResponse(responseCode = "415", description = "Tipo de medio no soportado")
    })
    public ResponseEntity<MessageResponse> changePassword(
            @Valid @RequestBody ChangePasswordRequest request, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(userService.changePassword(request, httpRequest));
    }

    @PostMapping("/recovery-codes/regenerate")
    @Operation(
            summary = "Regenerar los 8 códigos de recuperación de un solo uso",
            description =
                    "Requiere contraseña actual, reemplaza todos los códigos anteriores y devuelve"
                            + " los nuevos códigos en texto plano una única vez.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Códigos regenerados correctamente"),
        @ApiResponse(responseCode = "400", description = "Error de validación"),
        @ApiResponse(
                responseCode = "401",
                description = "Usuario no autenticado o contraseña actual incorrecta"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido"),
        @ApiResponse(responseCode = "415", description = "Tipo de medio no soportado")
    })
    public ResponseEntity<RecoveryCodesResponse> regenerateRecoveryCodes(
            @Valid @RequestBody RegenerateRecoveryCodesRequest request) {
        return ResponseEntity.ok(userService.regenerateRecoveryCodes(request));
    }

    @DeleteMapping
    @Operation(
            summary = "Eliminar permanentemente la cuenta y todos sus datos",
            description =
                    "Requiere contraseña actual. Elimina grupos, tareas, etiquetas, notificaciones,"
                            + " suscripciones Web Push, trabajos programados y sesiones activas.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Cuenta eliminada permanentemente"),
        @ApiResponse(responseCode = "400", description = "Error de validación"),
        @ApiResponse(
                responseCode = "401",
                description = "Usuario no autenticado o contraseña incorrecta"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido"),
        @ApiResponse(responseCode = "415", description = "Tipo de medio no soportado")
    })
    public ResponseEntity<Void> deleteAccount(
            @Valid @RequestBody DeleteAccountRequest request, HttpServletRequest httpRequest) {
        userService.deleteAccount(request, httpRequest);
        return ResponseEntity.noContent().build();
    }
}
