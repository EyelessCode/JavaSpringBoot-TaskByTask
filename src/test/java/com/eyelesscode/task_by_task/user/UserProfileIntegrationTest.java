package com.eyelesscode.task_by_task.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eyelesscode.task_by_task.shared.AbstractIntegrationTest;
import com.eyelesscode.task_by_task.shared.ratelimit.RateLimitService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class UserProfileIntegrationTest extends AbstractIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private SessionRepositoryFilter<? extends Session> sessionRepositoryFilter;
    @Autowired private UserRepository userRepository;
    @Autowired private RateLimitService rateLimitService;
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

    private record AuthenticatedUser(Long userId, Cookie sessionCookie) {}

    private AuthenticatedUser registerAndGetSession(String email, String username)
            throws Exception {
        String payload =
                String.format(
                        """
                        {
                          "email": "%s",
                          "username": "%s",
                          "displayName": "Original Name",
                          "password": "Password123",
                          "timezone": "UTC"
                        }
                        """,
                        email, username);

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

        return new AuthenticatedUser(userId, sessionCookie);
    }

    @Test
    @DisplayName(
            "Rechaza acceso sin sesión a GET y PATCH /api/v1/users/me con HTTP 401 RFC 7807"
                    + " (AUTH-REQ-30)")
    void shouldRejectUnauthenticatedProfileAccessWith401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("No autenticado"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(
                        jsonPath("$.detail")
                                .value("Debe iniciar sesión para acceder a este recurso"));

        mockMvc.perform(
                        patch("/api/v1/users/me")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"displayName\": \"Nuevo\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("No autenticado"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName(
            "GET /api/v1/users/me retorna el perfil y el conteo exacto de códigos de recuperación"
                    + " restantes sin exponer hashes (AUTH-REQ-17)")
    void shouldReturnUserProfileAndRemainingRecoveryCodesCount() throws Exception {
        AuthenticatedUser authUser = registerAndGetSession("me@example.com", "MeUser");

        // Simular que 3 códigos ya fueron usados
        jdbcTemplate.update(
                "UPDATE recovery_codes SET used = TRUE, used_at = CURRENT_TIMESTAMP WHERE id IN"
                        + " (SELECT id FROM recovery_codes WHERE user_id = ? LIMIT 3)",
                authUser.userId());

        mockMvc.perform(get("/api/v1/users/me").cookie(authUser.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(authUser.userId()))
                .andExpect(jsonPath("$.email").value("me@example.com"))
                .andExpect(jsonPath("$.username").value("MeUser"))
                .andExpect(jsonPath("$.displayName").value("Original Name"))
                .andExpect(jsonPath("$.timezone").value("UTC"))
                .andExpect(jsonPath("$.timeFormat").value("24H"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.remainingRecoveryCodes").value(5))
                .andExpect(jsonPath("$.recoveryCodes").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName(
            "PATCH /api/v1/users/me actualiza parcialmente el perfil y preserva PRINCIPAL_NAME ="
                    + " user.id en la sesión (AUTH-REQ-18, EC-01)")
    void shouldUpdateProfilePartiallyAndPreserveSessionPrincipalName() throws Exception {
        AuthenticatedUser authUser = registerAndGetSession("patch@example.com", "PatchUser");

        String patchPayload =
                """
                {
                  "displayName": "  Updated Name  ",
                  "username": "  UpdatedUser99  ",
                  "email": "  UPDATED@EXAMPLE.COM  ",
                  "timezone": "America/Mexico_City",
                  "timeFormat": "12H"
                }
                """;

        mockMvc.perform(
                        patch("/api/v1/users/me")
                                .cookie(authUser.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(patchPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(authUser.userId()))
                .andExpect(jsonPath("$.displayName").value("Updated Name"))
                .andExpect(jsonPath("$.username").value("UpdatedUser99"))
                .andExpect(jsonPath("$.email").value("updated@example.com"))
                .andExpect(jsonPath("$.timezone").value("America/Mexico_City"))
                .andExpect(jsonPath("$.timeFormat").value("12H"));

        // Verificar que la sesión sigue activa y con PRINCIPAL_NAME = user.id
        String principalName =
                jdbcTemplate.queryForObject(
                        "SELECT PRINCIPAL_NAME FROM SPRING_SESSION", String.class);
        assertThat(principalName).isEqualTo(String.valueOf(authUser.userId()));

        mockMvc.perform(get("/api/v1/users/me").cookie(authUser.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("updated@example.com"));
    }

    @Test
    @DisplayName(
            "PATCH /api/v1/users/me no lanza conflicto 409 cuando el usuario envía su propio email"
                    + " o username (EC-02)")
    void shouldNotConflictWithOwnEmailOrUsernameOnPatch() throws Exception {
        AuthenticatedUser authUser = registerAndGetSession("self@example.com", "SelfUser");

        String sameValuesWithDifferentCase =
                """
                {
                  "email": "SELF@EXAMPLE.COM",
                  "username": "selfuser"
                }
                """;

        mockMvc.perform(
                        patch("/api/v1/users/me")
                                .cookie(authUser.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(sameValuesWithDifferentCase))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("self@example.com"))
                .andExpect(jsonPath("$.username").value("selfuser"));
    }

    @Test
    @DisplayName(
            "PATCH /api/v1/users/me rechaza con HTTP 409 Conflict si email o username pertenecen a"
                    + " otro usuario (AUTH-REQ-20)")
    void shouldRejectPatchWith409WhenEmailOrUsernameTakenByAnotherUser() throws Exception {
        registerAndGetSession("other@example.com", "OtherUser");
        AuthenticatedUser currentUser = registerAndGetSession("current@example.com", "CurrentUser");

        // Conflicto de email
        mockMvc.perform(
                        patch("/api/v1/users/me")
                                .cookie(currentUser.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\": \"OTHER@EXAMPLE.COM\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflicto de estado"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("El correo electrónico ya está registrado"));

        // Conflicto de username
        mockMvc.perform(
                        patch("/api/v1/users/me")
                                .cookie(currentUser.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\": \"otheruser\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflicto de estado"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("El nombre de usuario ya está en uso"));
    }

    @Test
    @DisplayName(
            "PATCH /api/v1/users/me rechaza con HTTP 400 si timeFormat no es 24H o 12H o si"
                    + " timezone es inválida (AUTH-REQ-19, EC-07)")
    void shouldRejectInvalidTimeFormatOrTimezoneWith400() throws Exception {
        AuthenticatedUser authUser = registerAndGetSession("format@example.com", "FormatUser");

        mockMvc.perform(
                        patch("/api/v1/users/me")
                                .cookie(authUser.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"timeFormat\": \"AMPM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Error de validación"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("El formato de hora debe ser 24H o 12H"));

        mockMvc.perform(
                        patch("/api/v1/users/me")
                                .cookie(authUser.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"timezone\": \"Invalid/Timezone\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail").value("La zona horaria proporcionada no es válida"));
    }
}
