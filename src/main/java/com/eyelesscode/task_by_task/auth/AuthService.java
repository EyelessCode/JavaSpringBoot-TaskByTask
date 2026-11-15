package com.eyelesscode.task_by_task.auth;

import com.eyelesscode.task_by_task.auth.dto.RegisterRequest;
import com.eyelesscode.task_by_task.auth.dto.RegisterResponse;
import com.eyelesscode.task_by_task.group.Group;
import com.eyelesscode.task_by_task.group.GroupMembership;
import com.eyelesscode.task_by_task.group.GroupMembershipRepository;
import com.eyelesscode.task_by_task.group.GroupRepository;
import com.eyelesscode.task_by_task.shared.error.BadRequestException;
import com.eyelesscode.task_by_task.shared.error.ResourceConflictException;
import com.eyelesscode.task_by_task.shared.ratelimit.RateLimitService;
import com.eyelesscode.task_by_task.shared.time.DateTimeProvider;
import com.eyelesscode.task_by_task.user.User;
import com.eyelesscode.task_by_task.user.UserRepository;
import com.eyelesscode.task_by_task.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final String DEFAULT_TIMEZONE = "UTC";
    private static final String DEFAULT_TIME_FORMAT = "24H";
    private static final String PERSONAL_GROUP_NAME = "Personal";
    private static final String ROLE_CREATOR = "CREATOR";

    private final UserRepository userRepository;
    private final GroupRepository groupRepository;
    private final GroupMembershipRepository groupMembershipRepository;
    private final RecoveryCodeRepository recoveryCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final RateLimitService rateLimitService;
    private final DateTimeProvider dateTimeProvider;

    public AuthService(
            UserRepository userRepository,
            GroupRepository groupRepository,
            GroupMembershipRepository groupMembershipRepository,
            RecoveryCodeRepository recoveryCodeRepository,
            PasswordEncoder passwordEncoder,
            RateLimitService rateLimitService,
            DateTimeProvider dateTimeProvider) {
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
        this.groupMembershipRepository = groupMembershipRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.passwordEncoder = passwordEncoder;
        this.rateLimitService = rateLimitService;
        this.dateTimeProvider = dateTimeProvider;
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request, HttpServletRequest httpRequest) {
        rateLimitService.consumeOrThrow(
                rateLimitService.buildPartitionKey(httpRequest, "register"));

        String resolvedTimezone = resolveAndValidateTimezone(request.timezone());
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        String normalizedUsername = request.username().trim();
        String normalizedDisplayName = request.displayName().trim();

        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ResourceConflictException("El correo electrónico ya está registrado");
        }
        if (userRepository.existsByUsernameIgnoreCase(normalizedUsername)) {
            throw new ResourceConflictException("El nombre de usuario ya está en uso");
        }

        Instant now = dateTimeProvider.now();

        User user =
                new User(
                        normalizedEmail,
                        normalizedUsername,
                        normalizedDisplayName,
                        passwordEncoder.encode(request.password()),
                        resolvedTimezone,
                        DEFAULT_TIME_FORMAT,
                        now,
                        now);
        user = userRepository.save(user);

        Group personalGroup = new Group(user, PERSONAL_GROUP_NAME, true, now, now);
        personalGroup = groupRepository.save(personalGroup);

        GroupMembership creatorMembership =
                new GroupMembership(personalGroup, user, ROLE_CREATOR, now);
        groupMembershipRepository.save(creatorMembership);

        List<String> plaintextCodes = RecoveryCodeGenerator.generateCodes();
        List<RecoveryCode> recoveryCodeEntities = new ArrayList<>(plaintextCodes.size());
        for (String plainCode : plaintextCodes) {
            recoveryCodeEntities.add(
                    new RecoveryCode(user, passwordEncoder.encode(plainCode), false, null, now));
        }
        recoveryCodeRepository.saveAll(recoveryCodeEntities);

        establishAuthenticatedSession(user, httpRequest);

        UserResponse userResponse =
                new UserResponse(
                        user.getId(),
                        user.getEmail(),
                        user.getUsername(),
                        user.getDisplayName(),
                        user.getTimezone(),
                        user.getTimeFormat(),
                        user.getCreatedAt(),
                        plaintextCodes.size());

        return new RegisterResponse(userResponse, plaintextCodes);
    }

    private String resolveAndValidateTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return DEFAULT_TIMEZONE;
        }
        String trimmed = timezone.trim();
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

    private void establishAuthenticatedSession(User user, HttpServletRequest httpRequest) {
        String principalId = String.valueOf(user.getId());
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principalId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);

        HttpSession existingSession = httpRequest.getSession(false);
        if (existingSession != null) {
            existingSession.invalidate();
        }
        HttpSession session = httpRequest.getSession(true);
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
        session.setAttribute(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, principalId);
    }
}
