package com.eyelesscode.task_by_task.auth;

import com.eyelesscode.task_by_task.auth.dto.CsrfTokenResponse;
import com.eyelesscode.task_by_task.auth.dto.LoginRequest;
import com.eyelesscode.task_by_task.auth.dto.LogoutRequest;
import com.eyelesscode.task_by_task.auth.dto.MessageResponse;
import com.eyelesscode.task_by_task.auth.dto.RecoverPasswordRequest;
import com.eyelesscode.task_by_task.auth.dto.RegisterRequest;
import com.eyelesscode.task_by_task.auth.dto.RegisterResponse;
import com.eyelesscode.task_by_task.shared.config.OpenApiConfig;
import com.eyelesscode.task_by_task.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(
        name = "Auth",
        description = "Registro, inicio/cierre de sesión, recuperación y bootstrap CSRF")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/csrf")
    @SecurityRequirements
    @Operation(
            summary = "Obtener token CSRF y cookie XSRF-TOKEN",
            description =
                    "Endpoint público de bootstrap para clientes SPA. Emite la cookie `XSRF-TOKEN`"
                            + " y devuelve el token esperado en la cabecera `X-XSRF-TOKEN`.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Token CSRF emitido correctamente")
    })
    public ResponseEntity<CsrfTokenResponse> csrf(CsrfToken csrfToken) {
        return ResponseEntity.ok(
                new CsrfTokenResponse(
                        csrfToken.getToken(),
                        csrfToken.getHeaderName(),
                        csrfToken.getParameterName()));
    }

    @PostMapping("/register")
    @SecurityRequirements({@SecurityRequirement(name = OpenApiConfig.SECURITY_CSRF_TOKEN)})
    @Operation(
            summary = "Registrar una nueva cuenta de usuario",
            description =
                    "Crea la cuenta, genera el grupo predeterminado 'Personal' y 8 códigos de"
                            + " recuperación de un solo uso, e inicia sesión mediante cookie"
                            + " SESSION.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Usuario registrado y sesión iniciada correctamente"),
        @ApiResponse(
                responseCode = "400",
                description = "Error de validación o zona horaria IANA inválida"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido"),
        @ApiResponse(
                responseCode = "409",
                description = "El correo electrónico o nombre de usuario ya existe"),
        @ApiResponse(responseCode = "415", description = "Tipo de medio no soportado"),
        @ApiResponse(responseCode = "429", description = "Límite de solicitudes excedido")
    })
    public ResponseEntity<RegisterResponse> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        RegisterResponse response = authService.register(request, httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    @SecurityRequirements({@SecurityRequirement(name = OpenApiConfig.SECURITY_CSRF_TOKEN)})
    @Operation(
            summary = "Iniciar sesión con correo y contraseña",
            description =
                    "Autentica al usuario, crea una sesión persistente (7 días, cookie HttpOnly"
                            + " SESSION) y actualiza opcionalmente su zona horaria IANA.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Sesión iniciada correctamente"),
        @ApiResponse(
                responseCode = "400",
                description = "Datos inválidos o zona horaria IANA inválida"),
        @ApiResponse(responseCode = "401", description = "Credenciales inválidas"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido"),
        @ApiResponse(responseCode = "415", description = "Tipo de medio no soportado"),
        @ApiResponse(responseCode = "429", description = "Límite de solicitudes excedido")
    })
    public ResponseEntity<UserResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        UserResponse response = authService.login(request, httpRequest);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    @Operation(
            summary = "Cerrar sesión actual",
            description =
                    "Invalida la sesión activa, limpia la cookie SESSION y elimina opcionalmente la"
                            + " suscripción Web Push del dispositivo indicada en `endpoint`.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Sesión cerrada correctamente"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido"),
        @ApiResponse(responseCode = "415", description = "Tipo de medio no soportado")
    })
    public ResponseEntity<Void> logout(
            @RequestBody(required = false) LogoutRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        authService.logout(request, httpRequest, httpResponse);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/recover-password")
    @SecurityRequirements({@SecurityRequirement(name = OpenApiConfig.SECURITY_CSRF_TOKEN)})
    @Operation(
            summary = "Restablecer contraseña con código de recuperación de un solo uso",
            description =
                    "Consume un código de recuperación no utilizado, actualiza la contraseña e"
                            + " invalida todas las sesiones activas del usuario.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Contraseña restablecida exitosamente"),
        @ApiResponse(
                responseCode = "400",
                description = "Error de validación o código de recuperación inválido/usado"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido"),
        @ApiResponse(responseCode = "415", description = "Tipo de medio no soportado"),
        @ApiResponse(responseCode = "429", description = "Límite de solicitudes excedido")
    })
    public ResponseEntity<MessageResponse> recoverPassword(
            @Valid @RequestBody RecoverPasswordRequest request, HttpServletRequest httpRequest) {
        MessageResponse response = authService.recoverPassword(request, httpRequest);
        return ResponseEntity.ok(response);
    }
}
