package com.eyelesscode.task_by_task.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eyelesscode.task_by_task.auth.RecoveryCodeRepository;
import com.eyelesscode.task_by_task.shared.AbstractIntegrationTest;
import com.eyelesscode.task_by_task.shared.ratelimit.RateLimitService;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

class UserPasswordAndRecoveryCodesIntegrationTest extends AbstractIntegrationTest {

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

    private record RegisteredSessionFixture(
            Long userId, Cookie sessionCookie, List<String> initialRecoveryCodes) {}

    private RegisteredSessionFixture registerUser(String email, String username, String password)
            throws Exception {
        String payload =
                String.format(
                        """
                        {
                          "email": "%s",
                          "username": "%s",
                          "displayName": "Security User",
                          "password": "%s",
                          "timezone": "UTC"
                        }
                        """,
                        email, username, password);

        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/auth/register")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload))
                        .andExpect(status().isCreated())
                        .andReturn();

        rateLimitService.clear();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        Long userId = root.get("user").get("id").asLong();
        Cookie sessionCookie = result.getResponse().getCookie("SESSION");
        assertThat(sessionCookie).isNotNull();

        List<String> codes = new ArrayList<>();
        for (JsonNode codeNode : root.get("recoveryCodes")) {
            codes.add(codeNode.asText());
        }

        return new RegisteredSessionFixture(userId, sessionCookie, codes);
    }

    @Test
    @DisplayName(
            "Rechaza acceso sin sesión a cambio de clave y regeneración de códigos con HTTP 401"
                    + " (AUTH-REQ-30)")
    void shouldRejectUnauthenticatedPasswordAndRegenerateRequestsWith401() throws Exception {
        mockMvc.perform(
                        post("/api/v1/users/me/password")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"currentPassword\": \"Old123\", \"newPassword\":"
                                                + " \"New123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("No autenticado"))
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(
                        post("/api/v1/users/me/recovery-codes/regenerate")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"currentPassword\": \"Old123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("No autenticado"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName(
            "Cambio de contraseña actualiza el hash {bcrypt}, invalida otras sesiones activas y"
                    + " preserva la sesión actual del dispositivo (AUTH-REQ-21, EC-06)")
    void shouldChangePasswordInvalidateOtherSessionsAndPreserveCurrentSession() throws Exception {
        RegisteredSessionFixture fixture =
                registerUser("pwd@example.com", "PwdUser", "InitialPass123");

        // Crear una segunda sesión iniciando sesión en otro dispositivo
        MvcResult secondLogin =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "email": "pwd@example.com",
                                                  "password": "InitialPass123"
                                                }
                                                """))
                        .andExpect(status().isOk())
                        .andReturn();
        Cookie secondDeviceCookie = secondLogin.getResponse().getCookie("SESSION");
        assertThat(secondDeviceCookie).isNotNull();

        Integer sessionCountBefore =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?",
                        Integer.class,
                        String.valueOf(fixture.userId()));
        assertThat(sessionCountBefore).isEqualTo(2);

        // Cambiar contraseña usando la primera sesión (fixture.sessionCookie())
        String changePasswordPayload =
                """
                {
                  "currentPassword": "InitialPass123",
                  "newPassword": "UpdatedSecurePass99"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/users/me/password")
                                .cookie(fixture.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(changePasswordPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Contraseña actualizada exitosamente"));

        // Verificar que solo queda 1 sesión en SPRING_SESSION
        Integer sessionCountAfter =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?",
                        Integer.class,
                        String.valueOf(fixture.userId()));
        assertThat(sessionCountAfter).isEqualTo(1);

        // La sesión actual sigue funcionando (200 OK)
        mockMvc.perform(get("/api/v1/users/me").cookie(fixture.sessionCookie()))
                .andExpect(status().isOk());

        // La segunda sesión fue invalidada (401 Unauthorized)
        mockMvc.perform(get("/api/v1/users/me").cookie(secondDeviceCookie))
                .andExpect(status().isUnauthorized());

        // El hash fue actualizado con {bcrypt}
        User updatedUser = userRepository.findById(fixture.userId()).orElseThrow();
        assertThat(passwordEncoder.matches("UpdatedSecurePass99", updatedUser.getPasswordHash()))
                .isTrue();
    }

    @Test
    @DisplayName(
            "Rechaza cambio de contraseña con HTTP 400 si currentPassword es incorrecta o"
                    + " newPassword incumple longitud (AUTH-REQ-22, EC-10)")
    void shouldRejectChangePasswordWithWrongCurrentPasswordOrInvalidNewPassword() throws Exception {
        RegisteredSessionFixture fixture =
                registerUser("wrongpwd@example.com", "WrongPwdUser", "InitialPass123");

        String wrongCurrentPayload =
                """
                {
                  "currentPassword": "WrongCurrentPassword",
                  "newPassword": "ValidNewPassword12"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/users/me/password")
                                .cookie(fixture.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(wrongCurrentPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Contraseña incorrecta"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail").value("La contraseña actual ingresada es incorrecta"));

        // Contraseña nueva demasiado corta (< 6)
        String shortNewPayload =
                """
                {
                  "currentPassword": "InitialPass123",
                  "newPassword": "123"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/users/me/password")
                                .cookie(fixture.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(shortNewPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Error de validación"))
                .andExpect(jsonPath("$.status").value(400));

        // Verificar que la contraseña original no fue alterada
        User user = userRepository.findById(fixture.userId()).orElseThrow();
        assertThat(passwordEncoder.matches("InitialPass123", user.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName(
            "Regenera códigos de recuperación eliminando físicamente los anteriores e insertando 8"
                    + " nuevos códigos únicos (AUTH-REQ-23)")
    void shouldRegenerateRecoveryCodesPhysicallyDeletingOldOnes() throws Exception {
        RegisteredSessionFixture fixture =
                registerUser("regen@example.com", "RegenUser", "InitialPass123");

        // Simular que 2 códigos fueron usados antes de regenerar
        jdbcTemplate.update(
                "UPDATE recovery_codes SET used = TRUE, used_at = CURRENT_TIMESTAMP WHERE id IN"
                        + " (SELECT id FROM recovery_codes WHERE user_id = ? LIMIT 2)",
                fixture.userId());
        assertThat(recoveryCodeRepository.countByUserIdAndUsedFalse(fixture.userId())).isEqualTo(6);

        MvcResult regenResult =
                mockMvc.perform(
                                post("/api/v1/users/me/recovery-codes/regenerate")
                                        .cookie(fixture.sessionCookie())
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"currentPassword\": \"InitialPass123\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.recoveryCodes", hasSize(8)))
                        .andExpect(
                                jsonPath("$.recoveryCodes[0]")
                                        .value(matchesPattern("^[A-Z0-9]{10}$")))
                        .andExpect(jsonPath("$.recoveryCodesNotice").isNotEmpty())
                        .andReturn();

        // Invariante en BD: exactamente 8 filas totales en recovery_codes para el usuario
        Integer totalRowsInDb =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM recovery_codes WHERE user_id = ?",
                        Integer.class,
                        fixture.userId());
        assertThat(totalRowsInDb).isEqualTo(8);
        assertThat(recoveryCodeRepository.countByUserIdAndUsedFalse(fixture.userId())).isEqualTo(8);

        // GET /api/v1/users/me refleja 8 códigos disponibles
        mockMvc.perform(get("/api/v1/users/me").cookie(fixture.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingRecoveryCodes").value(8));

        // Los códigos antiguos ya no sirven para recuperar contraseña
        String oldCodeRecoverPayload =
                String.format(
                        """
                        {
                          "email": "regen@example.com",
                          "recoveryCode": "%s",
                          "newPassword": "RecoveredPassword123"
                        }
                        """,
                        fixture.initialRecoveryCodes().getFirst());

        mockMvc.perform(
                        post("/api/v1/auth/recover-password")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(oldCodeRecoverPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Recuperación fallida"));

        // El nuevo código sí sirve para recuperar contraseña
        JsonNode regenJson = objectMapper.readTree(regenResult.getResponse().getContentAsString());
        String newFirstCode = regenJson.get("recoveryCodes").get(0).asText();

        String newCodeRecoverPayload =
                String.format(
                        """
                        {
                          "email": "regen@example.com",
                          "recoveryCode": "%s",
                          "newPassword": "RecoveredPassword123"
                        }
                        """,
                        newFirstCode);

        mockMvc.perform(
                        post("/api/v1/auth/recover-password")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(newCodeRecoverPayload))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName(
            "Rechaza regeneración de códigos con HTTP 400 si currentPassword es incorrecta,"
                    + " preservando los códigos existentes (AUTH-REQ-24, EC-10)")
    void shouldRejectRegenerateRecoveryCodesWhenPasswordIsIncorrect() throws Exception {
        RegisteredSessionFixture fixture =
                registerUser("regenfail@example.com", "RegenFailUser", "InitialPass123");

        mockMvc.perform(
                        post("/api/v1/users/me/recovery-codes/regenerate")
                                .cookie(fixture.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"currentPassword\": \"WrongPassword\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Contraseña incorrecta"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail").value("La contraseña actual ingresada es incorrecta"));

        // Verificar que el código original sigue siendo válido
        String firstInitialCode = fixture.initialRecoveryCodes().getFirst();
        boolean stillMatches =
                recoveryCodeRepository.findByUserIdAndUsedFalse(fixture.userId()).stream()
                        .anyMatch(
                                rc -> passwordEncoder.matches(firstInitialCode, rc.getCodeHash()));
        assertThat(stillMatches).isTrue();
    }
}
