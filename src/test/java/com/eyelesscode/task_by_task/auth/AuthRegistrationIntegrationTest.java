package com.eyelesscode.task_by_task.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eyelesscode.task_by_task.group.Group;
import com.eyelesscode.task_by_task.group.GroupMembership;
import com.eyelesscode.task_by_task.group.GroupMembershipRepository;
import com.eyelesscode.task_by_task.group.GroupRepository;
import com.eyelesscode.task_by_task.shared.AbstractIntegrationTest;
import com.eyelesscode.task_by_task.shared.ratelimit.RateLimitService;
import com.eyelesscode.task_by_task.user.User;
import com.eyelesscode.task_by_task.user.UserRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AuthRegistrationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private SessionRepositoryFilter<? extends Session> sessionRepositoryFilter;
    @Autowired private SessionRepository<? extends Session> sessionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupMembershipRepository groupMembershipRepository;
    @Autowired private RecoveryCodeRepository recoveryCodeRepository;
    @Autowired private RateLimitService rateLimitService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.webAppContextSetup(context)
                        .addFilters(sessionRepositoryFilter)
                        .apply(springSecurity())
                        .build();
        rateLimitService.clear();
        jdbcTemplate.execute("DELETE FROM SPRING_SESSION");
        userRepository.deleteAll();
    }

    @Test
    @DisplayName(
            "Registro exitoso crea cuenta, grupo Personal con rol CREATOR, 8 códigos {bcrypt}, e"
                    + " inicia sesión persistente (AUTH-REQ-01, 05, 06, 07)")
    void shouldRegisterUserAtomicallyWithPersonalGroupRecoveryCodesAndPersistentSession()
            throws Exception {
        String payload =
                """
                {
                  "email": "alice@example.com",
                  "username": "Alice2026",
                  "displayName": "Alice Pérez",
                  "password": "SecretPassword1",
                  "timezone": "America/Bogota"
                }
                """;

        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/auth/register")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload))
                        .andExpect(status().isCreated())
                        .andExpect(
                                header().string(HttpHeaders.SET_COOKIE, containsString("SESSION=")))
                        .andExpect(jsonPath("$.user.id").isNumber())
                        .andExpect(jsonPath("$.user.email").value("alice@example.com"))
                        .andExpect(jsonPath("$.user.username").value("Alice2026"))
                        .andExpect(jsonPath("$.user.displayName").value("Alice Pérez"))
                        .andExpect(jsonPath("$.user.timezone").value("America/Bogota"))
                        .andExpect(jsonPath("$.user.timeFormat").value("24H"))
                        .andExpect(jsonPath("$.user.createdAt").isNotEmpty())
                        .andExpect(jsonPath("$.user.remainingRecoveryCodes").value(8))
                        .andExpect(jsonPath("$.recoveryCodes", hasSize(8)))
                        .andExpect(
                                jsonPath("$.recoveryCodes[0]")
                                        .value(matchesPattern("^[A-Z0-9]{10}$")))
                        .andExpect(jsonPath("$.recoveryCodesNotice").isNotEmpty())
                        .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        Long userId = root.get("user").get("id").asLong();

        // Verificar usuario persistido con {bcrypt}
        User savedUser = userRepository.findById(userId).orElseThrow();
        assertThat(savedUser.getPasswordHash()).startsWith("{bcrypt}");
        assertThat(passwordEncoder.matches("SecretPassword1", savedUser.getPasswordHash()))
                .isTrue();

        // Verificar grupo "Personal" (is_personal = true) y membresía CREATOR (RNF-20)
        Group personalGroup =
                groupRepository.findByCreatorIdAndIsPersonalTrue(userId).orElseThrow();
        assertThat(personalGroup.getName()).isEqualTo("Personal");
        assertThat(personalGroup.isPersonal()).isTrue();

        List<GroupMembership> memberships = groupMembershipRepository.findByUserId(userId);
        assertThat(memberships).hasSize(1);
        assertThat(memberships.getFirst().getGroup().getId()).isEqualTo(personalGroup.getId());
        assertThat(memberships.getFirst().getRole()).isEqualTo("CREATOR");

        // Verificar 8 códigos de recuperación hasheados con {bcrypt}
        List<RecoveryCode> storedCodes = recoveryCodeRepository.findByUserIdAndUsedFalse(userId);
        assertThat(storedCodes).hasSize(8);
        String firstPlainCode = root.get("recoveryCodes").get(0).asText();
        assertThat(
                        storedCodes.stream()
                                .anyMatch(
                                        rc ->
                                                rc.getCodeHash().startsWith("{bcrypt}")
                                                        && passwordEncoder.matches(
                                                                firstPlainCode, rc.getCodeHash())))
                .isTrue();

        // Verificar sesión persistida en SPRING_SESSION con PRINCIPAL_NAME = String.valueOf(userId)
        String principalName =
                jdbcTemplate.queryForObject(
                        "SELECT PRINCIPAL_NAME FROM SPRING_SESSION", String.class);
        assertThat(principalName).isEqualTo(String.valueOf(userId));
    }

    @Test
    @DisplayName(
            "Normaliza espacios y mayúsculas en email/username y asigna UTC si timezone se omite"
                    + " (EC-01, AUTH-REQ-01)")
    void shouldNormalizeIdentifiersAndDefaultTimezoneToUtcWhenOmitted() throws Exception {
        String payload =
                """
                {
                  "email": "  Bob.Smith@Domain.COM  ",
                  "username": "  BobUser99  ",
                  "displayName": "  Bob Smith  ",
                  "password": "Password123"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value("bob.smith@domain.com"))
                .andExpect(jsonPath("$.user.username").value("BobUser99"))
                .andExpect(jsonPath("$.user.displayName").value("Bob Smith"))
                .andExpect(jsonPath("$.user.timezone").value("UTC"))
                .andExpect(jsonPath("$.user.timeFormat").value("24H"));
    }

    @Test
    @DisplayName(
            "Rechaza con HTTP 409 Conflict si el email o username ya existen sin importar"
                    + " mayúsculas (AUTH-REQ-03)")
    void shouldRejectDuplicateEmailOrUsernameCaseInsensitivelyWith409() throws Exception {
        String firstPayload =
                """
                {
                  "email": "carlos@example.com",
                  "username": "CarlosDev",
                  "displayName": "Carlos",
                  "password": "Password123"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(firstPayload))
                .andExpect(status().isCreated());

        // Email duplicado en mayúsculas
        String duplicateEmailPayload =
                """
                {
                  "email": "CARLOS@EXAMPLE.COM",
                  "username": "OtroUser",
                  "displayName": "Otro",
                  "password": "Password123"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(duplicateEmailPayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflicto de estado"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("El correo electrónico ya está registrado"));

        // Username duplicado en minúsculas
        String duplicateUsernamePayload =
                """
                {
                  "email": "otro@example.com",
                  "username": "carlosdev",
                  "displayName": "Otro",
                  "password": "Password123"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(duplicateUsernamePayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflicto de estado"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("El nombre de usuario ya está en uso"));
    }

    @Test
    @DisplayName(
            "Rechaza con HTTP 400 Bad Request y lista de errores en español ante campos inválidos"
                    + " (AUTH-REQ-02, AUTH-REQ-04)")
    void shouldRejectInvalidFieldsWith400AndSpanishValidationDetails() throws Exception {
        String invalidPayload =
                """
                {
                  "email": "not-an-email",
                  "username": "ab_!",
                  "displayName": "A",
                  "password": "123"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Error de validación"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.invalidParams", hasSize(4)));
    }

    @Test
    @DisplayName(
            "Rechaza con HTTP 400 Bad Request si la zona horaria no pertenece al catálogo IANA"
                    + " (EC-07)")
    void shouldRejectInvalidTimezoneWith400AndNotCreateUser() throws Exception {
        String invalidTzPayload =
                """
                {
                  "email": "diana@example.com",
                  "username": "Diana2026",
                  "displayName": "Diana",
                  "password": "Password123",
                  "timezone": "UTC+5"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidTzPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"))
                .andExpect(
                        jsonPath("$.detail").value("La zona horaria proporcionada no es válida"));

        assertThat(userRepository.count()).isZero();
    }
}
