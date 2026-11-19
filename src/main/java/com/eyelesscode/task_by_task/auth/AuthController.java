package com.eyelesscode.task_by_task.auth;

import com.eyelesscode.task_by_task.auth.dto.CsrfTokenResponse;
import com.eyelesscode.task_by_task.auth.dto.LoginRequest;
import com.eyelesscode.task_by_task.auth.dto.LogoutRequest;
import com.eyelesscode.task_by_task.auth.dto.MessageResponse;
import com.eyelesscode.task_by_task.auth.dto.RecoverPasswordRequest;
import com.eyelesscode.task_by_task.auth.dto.RegisterRequest;
import com.eyelesscode.task_by_task.auth.dto.RegisterResponse;
import com.eyelesscode.task_by_task.user.dto.UserResponse;
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
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/csrf")
    public ResponseEntity<CsrfTokenResponse> csrf(CsrfToken csrfToken) {
        return ResponseEntity.ok(
                new CsrfTokenResponse(
                        csrfToken.getToken(),
                        csrfToken.getHeaderName(),
                        csrfToken.getParameterName()));
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        RegisterResponse response = authService.register(request, httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<UserResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        UserResponse response = authService.login(request, httpRequest);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestBody(required = false) LogoutRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        authService.logout(request, httpRequest, httpResponse);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/recover-password")
    public ResponseEntity<MessageResponse> recoverPassword(
            @Valid @RequestBody RecoverPasswordRequest request, HttpServletRequest httpRequest) {
        MessageResponse response = authService.recoverPassword(request, httpRequest);
        return ResponseEntity.ok(response);
    }
}
