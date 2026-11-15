package com.eyelesscode.task_by_task.user;

import com.eyelesscode.task_by_task.auth.RecoveryCode;
import com.eyelesscode.task_by_task.auth.RecoveryCodeGenerator;
import com.eyelesscode.task_by_task.auth.RecoveryCodeRepository;
import com.eyelesscode.task_by_task.auth.dto.MessageResponse;
import com.eyelesscode.task_by_task.shared.error.BadRequestException;
import com.eyelesscode.task_by_task.shared.error.ResourceConflictException;
import com.eyelesscode.task_by_task.shared.error.ResourceNotFoundException;
import com.eyelesscode.task_by_task.shared.security.SecurityUtils;
import com.eyelesscode.task_by_task.shared.time.DateTimeProvider;
import com.eyelesscode.task_by_task.user.dto.ChangePasswordRequest;
import com.eyelesscode.task_by_task.user.dto.DeleteAccountRequest;
import com.eyelesscode.task_by_task.user.dto.RecoveryCodesResponse;
import com.eyelesscode.task_by_task.user.dto.RegenerateRecoveryCodesRequest;
import com.eyelesscode.task_by_task.user.dto.UpdateProfileRequest;
import com.eyelesscode.task_by_task.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private static final Set<String> ALLOWED_TIME_FORMATS = Set.of("24H", "12H");
    private static final String WRONG_PASSWORD_TITLE = "Contraseña incorrecta";
    private static final String WRONG_CURRENT_PASSWORD_DETAIL =
            "La contraseña actual ingresada es incorrecta";
    private static final String WRONG_DELETE_PASSWORD_DETAIL =
            "La contraseña ingresada es incorrecta";

    private final UserRepository userRepository;
    private final RecoveryCodeRepository recoveryCodeRepository;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final DateTimeProvider dateTimeProvider;
    private final JdbcTemplate jdbcTemplate;

    public UserService(
            UserRepository userRepository,
            RecoveryCodeRepository recoveryCodeRepository,
            FindByIndexNameSessionRepository<? extends Session> sessionRepository,
            PasswordEncoder passwordEncoder,
            DateTimeProvider dateTimeProvider,
            JdbcTemplate jdbcTemplate) {
        this.userRepository = userRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.dateTimeProvider = dateTimeProvider;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public UserResponse getProfile() {
        Long userId = SecurityUtils.getCurrentUserId();
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        int remainingCodes = (int) recoveryCodeRepository.countByUserIdAndUsedFalse(userId);
        return UserMapper.toResponse(user, remainingCodes);
    }

    @Transactional
    public UserResponse updateProfile(UpdateProfileRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        boolean modified = false;

        if (request.timeFormat() != null) {
            if (!ALLOWED_TIME_FORMATS.contains(request.timeFormat())) {
                throw new BadRequestException(
                        "Error de validación", "El formato de hora debe ser 24H o 12H");
            }
            if (!request.timeFormat().equals(user.getTimeFormat())) {
                user.setTimeFormat(request.timeFormat());
                modified = true;
            }
        }

        if (request.timezone() != null) {
            String validatedTimezone = validateTimezone(request.timezone());
            if (!validatedTimezone.equals(user.getTimezone())) {
                user.setTimezone(validatedTimezone);
                modified = true;
            }
        }

        if (request.email() != null) {
            String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
            if (userRepository.existsByEmailIgnoreCaseAndIdNot(normalizedEmail, userId)) {
                throw new ResourceConflictException("El correo electrónico ya está registrado");
            }
            if (!normalizedEmail.equals(user.getEmail())) {
                user.setEmail(normalizedEmail);
                modified = true;
            }
        }

        if (request.username() != null) {
            String normalizedUsername = request.username().trim();
            if (userRepository.existsByUsernameIgnoreCaseAndIdNot(normalizedUsername, userId)) {
                throw new ResourceConflictException("El nombre de usuario ya está en uso");
            }
            if (!normalizedUsername.equals(user.getUsername())) {
                user.setUsername(normalizedUsername);
                modified = true;
            }
        }

        if (request.displayName() != null) {
            String normalizedDisplayName = request.displayName().trim();
            if (!normalizedDisplayName.equals(user.getDisplayName())) {
                user.setDisplayName(normalizedDisplayName);
                modified = true;
            }
        }

        if (modified) {
            user.setUpdatedAt(dateTimeProvider.now());
            user = userRepository.save(user);
        }

        int remainingCodes = (int) recoveryCodeRepository.countByUserIdAndUsedFalse(userId);
        return UserMapper.toResponse(user, remainingCodes);
    }

    @Transactional
    public MessageResponse changePassword(
            ChangePasswordRequest request, HttpServletRequest httpRequest) {
        Long userId = SecurityUtils.getCurrentUserId();
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BadRequestException(WRONG_PASSWORD_TITLE, WRONG_CURRENT_PASSWORD_DETAIL);
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setUpdatedAt(dateTimeProvider.now());
        userRepository.save(user);

        HttpSession currentHttpSession = httpRequest.getSession(false);
        String currentSessionId = currentHttpSession != null ? currentHttpSession.getId() : null;

        Map<String, ? extends Session> userSessions =
                sessionRepository.findByPrincipalName(String.valueOf(userId));
        for (String sessionId : userSessions.keySet()) {
            if (!sessionId.equals(currentSessionId)) {
                sessionRepository.deleteById(sessionId);
            }
        }

        return new MessageResponse("Contraseña actualizada exitosamente");
    }

    @Transactional
    public RecoveryCodesResponse regenerateRecoveryCodes(RegenerateRecoveryCodesRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BadRequestException(WRONG_PASSWORD_TITLE, WRONG_CURRENT_PASSWORD_DETAIL);
        }

        recoveryCodeRepository.deleteByUserId(userId);

        Instant now = dateTimeProvider.now();
        List<String> plaintextCodes = RecoveryCodeGenerator.generateCodes();
        List<RecoveryCode> recoveryCodeEntities = new ArrayList<>(plaintextCodes.size());
        for (String plainCode : plaintextCodes) {
            recoveryCodeEntities.add(
                    new RecoveryCode(user, passwordEncoder.encode(plainCode), false, null, now));
        }
        recoveryCodeRepository.saveAll(recoveryCodeEntities);

        return new RecoveryCodesResponse(plaintextCodes);
    }

    @Transactional
    public void deleteAccount(DeleteAccountRequest request, HttpServletRequest httpRequest) {
        Long userId = SecurityUtils.getCurrentUserId();
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadRequestException(WRONG_PASSWORD_TITLE, WRONG_DELETE_PASSWORD_DETAIL);
        }

        jdbcTemplate.update(
                "DELETE FROM scheduled_tasks WHERE task_instance IN ("
                        + "SELECT 'task-due-' || t.id FROM tasks t JOIN groups g ON t.group_id ="
                        + " g.id WHERE g.creator_id = ?)",
                userId);

        Map<String, ? extends Session> userSessions =
                sessionRepository.findByPrincipalName(String.valueOf(userId));
        userSessions.keySet().forEach(sessionRepository::deleteById);

        userRepository.delete(user);
        userRepository.flush();

        HttpSession currentSession = httpRequest.getSession(false);
        if (currentSession != null) {
            currentSession.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    private String validateTimezone(String timezone) {
        String trimmed = timezone.trim();
        if (trimmed.isEmpty()) {
            throw new BadRequestException("La zona horaria proporcionada no es válida");
        }
        try {
            if (!ZoneId.getAvailableZoneIds().contains(trimmed)) {
                throw new BadRequestException("La zona horaria proporcionada no es válida");
            }
            ZoneId.of(trimmed);
            return trimmed;
        } catch (DateTimeException e) {
            throw new BadRequestException("La zona horaria proporcionada no es válida");
        }
    }
}
