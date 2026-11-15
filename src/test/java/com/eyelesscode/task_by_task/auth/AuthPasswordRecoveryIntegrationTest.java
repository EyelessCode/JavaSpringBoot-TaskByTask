package com.eyelesscode.task_by_task.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eyelesscode.task_by_task.shared.AbstractIntegrationTest;
import com.eyelesscode.task_by_task.shared.ratelimit.RateLimitService;
import com.eyelesscode.task_by_task.user.User;
import com.eyelesscode.task_by_task.user.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.Session;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AuthPasswordRecoveryIntegrationTest extends AbstractIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private SessionRepositoryFilter<? extends Session> sessionRepositoryFilter;
    @Autowired private UserRepository userRepository;
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

    private record RegisteredUserFixture(Long userId, String email, List<String> recoveryCodes) {}

    private RegisteredUserFixture registerUserAndGetCodes(String email, String username)
            throws Exception {
        String registerPayload =
                String.format(
                        """
                        {
                          "email": "%s",
                          "username": "%s",
                          "displayName": "Recovery Tester",
                          "password": "OldPassword123",
                          "timezone": "UTC"
                        }
                        """,
                        email, username);

        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/auth/register")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(registerPayload))
                        .andExpect(status().isCreated())
                        .andReturn();

        rateLimitService.clear();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        Long userId = root.get("user").get("id").asLong();
        List<String> codes = new ArrayList<>();
        for (JsonNode codeNode : root.get("recoveryCodes")) {
            codes.add(codeNode.asText());
        }
        return new RegisteredUserFixture(userId, email, codes);
    }

    @Test
    @DisplayName(
            "Recuperación exitosa normaliza el código (EC-11), consume el código (used = true,"
                    + " used_at != null), actualiza hash {bcrypt} e invalida todas las sesiones"
                    + " activas (AUTH-REQ-13, EC-06)")
    void shouldRecoverPasswordConsumeCodeAndInvalidateAllSessions() throws Exception {
        RegisteredUserFixture fixture =
                registerUserAndGetCodes("recover@example.com", "RecoverUser");

        // Crear una segunda sesión iniciando sesión desde otro cliente
        String loginPayload =
                """
                {
                  "email": "recover@example.com",
                  "password": "OldPassword123"
                }
                """;
        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginPayload))
                .andExpect(status().isOk());

        Integer activeSessionsBefore =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?",
                        Integer.class,
                        String.valueOf(fixture.userId()));
        assertThat(activeSessionsBefore).isEqualTo(2);

        // Enviar código en minúsculas y con espacios accidentales (EC-11)
        String rawCodeWithSpacesAndLower =
                "  " + fixture.recoveryCodes().getFirst().toLowerCase(Locale.ROOT) + "  ";
        String recoverPayload =
                String.format(
                        """
                        {
                          "email": "RECOVER@EXAMPLE.COM",
                          "recoveryCode": "%s",
                          "newPassword": "NewStrongPassword99"
                        }
                        """,
                        rawCodeWithSpacesAndLower);

        mockMvc.perform(
                        post("/api/v1/auth/recover-password")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(recoverPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Contraseña restablecida exitosamente"));

        // Verificar que todas las sesiones del usuario fueron eliminadas (EC-06)
        Integer activeSessionsAfter =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?",
                        Integer.class,
                        String.valueOf(fixture.userId()));
        assertThat(activeSessionsAfter).isZero();

        // Verificar que la nueva contraseña funciona y quedan 7 códigos sin usar
        User updatedUser = userRepository.findById(fixture.userId()).orElseThrow();
        assertThat(passwordEncoder.matches("NewStrongPassword99", updatedUser.getPasswordHash()))
                .isTrue();
        assertThat(recoveryCodeRepository.countByUserIdAndUsedFalse(fixture.userId())).isEqualTo(7);

        List<RecoveryCode> allCodes =
                recoveryCodeRepository.findAll().stream()
                        .filter(rc -> rc.getUser().getId().equals(fixture.userId()))
                        .toList();
        assertThat(allCodes.stream().filter(RecoveryCode::isUsed))
                .hasSize(1)
                .allSatisfy(rc -> assertThat(rc.getUsedAt()).isNotNull());
    }

    @Test
    @DisplayName(
            "Rechaza con el mismo HTTP 400 genérico si el código ya fue consumido (EC-03), es"
                    + " inválido (AUTH-REQ-15), están los 8 agotados (EC-04) o el correo no existe"
                    + " con timing check (AUTH-REQ-14, EC-12)")
    void shouldRejectUsedInvalidExhaustedOrNonExistentEmailWithIdenticalGeneric400()
            throws Exception {
        RegisteredUserFixture fixture = registerUserAndGetCodes("edge@example.com", "EdgeUser");
        String firstCode = fixture.recoveryCodes().getFirst();

        // 1. Consumir el primer código
        String recoverPayload =
                String.format(
                        """
                        {
                          "email": "edge@example.com",
                          "recoveryCode": "%s",
                          "newPassword": "PasswordAfterFirst"
                        }
                        """,
                        firstCode);

        mockMvc.perform(
                        post("/api/v1/auth/recover-password")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(recoverPayload))
                .andExpect(status().isOk());

        rateLimitService.clear();

        // 2. Intentar reusar el mismo código ya consumido (EC-03)
        mockMvc.perform(
                        post("/api/v1/auth/recover-password")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(recoverPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Recuperación fallida"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "El correo electrónico o el código de recuperación son"
                                                + " incorrectos, o el código ya fue utilizado"));

        // 3. Código inválido para correo existente (AUTH-REQ-15)
        String invalidCodePayload =
                """
                {
                  "email": "edge@example.com",
                  "recoveryCode": "ZZZZZZZZZZ",
                  "newPassword": "AnotherPassword12"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/auth/recover-password")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidCodePayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Recuperación fallida"))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "El correo electrónico o el código de recuperación son"
                                                + " incorrectos, o el código ya fue utilizado"));

        // 4. Correo inexistente con verificación simulada BCrypt (AUTH-REQ-14, EC-12)
        String nonExistentEmailPayload =
                """
                {
                  "email": "nobody@example.com",
                  "recoveryCode": "ABCDEFGHIJ",
                  "newPassword": "AnotherPassword12"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/auth/recover-password")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(nonExistentEmailPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Recuperación fallida"))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "El correo electrónico o el código de recuperación son"
                                                + " incorrectos, o el código ya fue utilizado"));

        // 5. Usuario con los 8 códigos agotados (EC-04)
        jdbcTemplate.update(
                "UPDATE recovery_codes SET used = TRUE, used_at = CURRENT_TIMESTAMP WHERE user_id ="
                        + " ?",
                fixture.userId());

        String exhaustedPayload =
                String.format(
                        """
                        {
                          "email": "edge@example.com",
                          "recoveryCode": "%s",
                          "newPassword": "AnotherPassword12"
                        }
                        """,
                        fixture.recoveryCodes().get(1));

        mockMvc.perform(
                        post("/api/v1/auth/recover-password")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(exhaustedPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Recuperación fallida"))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "El correo electrónico o el código de recuperación son"
                                                + " incorrectos, o el código ya fue utilizado"));
    }

    @Test
    @DisplayName(
            "Rechaza con HTTP 400 cuando la nueva contraseña incumple el rango de 6 a 30 caracteres"
                    + " (AUTH-REQ-16)")
    void shouldRejectInvalidNewPasswordLengthWith400() throws Exception {
        String shortPasswordPayload =
                """
                {
                  "email": "user@example.com",
                  "recoveryCode": "ABCDEFGHIJ",
                  "newPassword": "12345"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/auth/recover-password")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(shortPasswordPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Error de validación"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.invalidParams[0].name").value("newPassword"));
    }

    @Test
    @DisplayName(
            "Garantiza consumo atómico ante solicitudes concurrentes con el mismo código: solo una"
                    + " tiene éxito y la otra falla con 400 (EC-05)")
    void shouldAllowOnlyOneSuccessWhenSameRecoveryCodeIsUsedConcurrently() throws Exception {
        RegisteredUserFixture fixture =
                registerUserAndGetCodes("concurrent@example.com", "ConcurrentUser");
        String targetCode = fixture.recoveryCodes().getFirst();

        String payload =
                String.format(
                        """
                        {
                          "email": "concurrent@example.com",
                          "recoveryCode": "%s",
                          "newPassword": "ConcurrentNewPass1"
                        }
                        """,
                        targetCode);

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<Integer> task1 =
                () -> {
                    readyLatch.countDown();
                    startLatch.await();
                    return mockMvc.perform(
                                    post("/api/v1/auth/recover-password")
                                            .with(csrf())
                                            .header("X-Forwarded-For", "198.51.100.1")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(payload))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                };

        Callable<Integer> task2 =
                () -> {
                    readyLatch.countDown();
                    startLatch.await();
                    return mockMvc.perform(
                                    post("/api/v1/auth/recover-password")
                                            .with(csrf())
                                            .header("X-Forwarded-For", "198.51.100.2")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(payload))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                };

        try {
            Future<Integer> future1 = executor.submit(task1);
            Future<Integer> future2 = executor.submit(task2);
            readyLatch.await();
            startLatch.countDown();

            int status1 = future1.get();
            int status2 = future2.get();

            assertThat(List.of(status1, status2)).containsExactlyInAnyOrder(200, 400);
            assertThat(recoveryCodeRepository.countByUserIdAndUsedFalse(fixture.userId()))
                    .isEqualTo(7);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName(
            "Aplica rate limiting de 5 intentos por minuto en recover-password retornando HTTP 429"
                    + " con Retry-After (AUTH-REQ-27, AUTH-REQ-28)")
    void shouldEnforceRateLimitOnRecoverPasswordAfterFiveAttempts() throws Exception {
        String payload =
                """
                {
                  "email": "nobody@example.com",
                  "recoveryCode": "ABCDEFGHIJ",
                  "newPassword": "ValidPassword123"
                }
                """;

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(
                            post("/api/v1/auth/recover-password")
                                    .with(csrf())
                                    .header("X-Forwarded-For", "203.0.113.77")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(
                        post("/api/v1/auth/recover-password")
                                .with(csrf())
                                .header("X-Forwarded-For", "203.0.113.77")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.status").value(429));
    }
}
