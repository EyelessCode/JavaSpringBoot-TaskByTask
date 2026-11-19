package com.eyelesscode.task_by_task.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eyelesscode.task_by_task.notification.PushSubscription;
import com.eyelesscode.task_by_task.notification.PushSubscriptionRepository;
import com.eyelesscode.task_by_task.shared.AbstractIntegrationTest;
import com.eyelesscode.task_by_task.shared.ratelimit.RateLimitService;
import com.eyelesscode.task_by_task.user.User;
import com.eyelesscode.task_by_task.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.support.WebTestUtils;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.session.Session;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AuthLoginLogoutIntegrationTest extends AbstractIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private SessionRepositoryFilter<? extends Session> sessionRepositoryFilter;
    @Autowired private UserRepository userRepository;
    @Autowired private PushSubscriptionRepository pushSubscriptionRepository;
    @Autowired private RateLimitService rateLimitService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CookieCsrfTokenRepository cookieCsrfTokenRepository;
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

    private Long registerTestUser(String email, String username, String password) throws Exception {
        String registerPayload =
                String.format(
                        """
                        {
                          "email": "%s",
                          "username": "%s",
                          "displayName": "Test User",
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
                                        .content(registerPayload))
                        .andExpect(status().isCreated())
                        .andReturn();

        rateLimitService.clear();
        jdbcTemplate.execute("DELETE FROM SPRING_SESSION");

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.get("user").get("id").asLong();
    }

    @Test
    @DisplayName(
            "Login exitoso autentica al usuario, crea sesión en SPRING_SESSION con PRINCIPAL_NAME ="
                    + " user.id y actualiza timezone si se envía (AUTH-REQ-08, AUTH-REQ-09)")
    void shouldLoginSuccessfullyCreatePersistentSessionAndUpdateTimezone() throws Exception {
        Long userId = registerTestUser("login@example.com", "LoginUser", "MySecret123");

        String loginPayload =
                """
                {
                  "email": "  LOGIN@EXAMPLE.COM  ",
                  "password": "MySecret123",
                  "timezone": "America/Lima"
                }
                """;

        MvcResult loginResult =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(loginPayload))
                        .andExpect(status().isOk())
                        .andExpect(
                                header().string(HttpHeaders.SET_COOKIE, containsString("SESSION=")))
                        .andExpect(jsonPath("$.id").value(userId))
                        .andExpect(jsonPath("$.email").value("login@example.com"))
                        .andExpect(jsonPath("$.username").value("LoginUser"))
                        .andExpect(jsonPath("$.timezone").value("America/Lima"))
                        .andExpect(jsonPath("$.remainingRecoveryCodes").value(8))
                        .andReturn();

        assertThat(loginResult.getResponse().getCookie("SESSION")).isNotNull();

        User updatedUser = userRepository.findById(userId).orElseThrow();
        assertThat(updatedUser.getTimezone()).isEqualTo("America/Lima");

        String principalName =
                jdbcTemplate.queryForObject(
                        "SELECT PRINCIPAL_NAME FROM SPRING_SESSION", String.class);
        assertThat(principalName).isEqualTo(String.valueOf(userId));
    }

    @Test
    @DisplayName(
            "Rechaza con HTTP 400 Bad Request si timezone en login no es IANA válida, sin crear"
                    + " sesión en SPRING_SESSION (AUTH-REQ-10, EC-07)")
    void shouldRejectLoginWithInvalidTimezoneWith400AndNotCreateSession() throws Exception {
        registerTestUser("tz@example.com", "TzUser", "MySecret123");

        String invalidTzLogin =
                """
                {
                  "email": "tz@example.com",
                  "password": "MySecret123",
                  "timezone": "Custom/Zone"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidTzLogin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail").value("La zona horaria proporcionada no es válida"));

        Integer sessionCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Integer.class);
        assertThat(sessionCount).isZero();
    }

    @Test
    @DisplayName(
            "Rechaza con HTTP 401 genérico ante correo inexistente o contraseña incorrecta sin"
                    + " revelar existencia (AUTH-REQ-11)")
    void shouldReturnGeneric401ForNonExistentEmailOrWrongPassword() throws Exception {
        registerTestUser("valid@example.com", "ValidUser", "MySecret123");

        String wrongPasswordPayload =
                """
                {
                  "email": "valid@example.com",
                  "password": "WrongPassword"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(wrongPasswordPayload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Credenciales inválidas"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(
                        jsonPath("$.detail")
                                .value("El correo electrónico o la contraseña son incorrectos"));

        String nonExistentEmailPayload =
                """
                {
                  "email": "ghost@example.com",
                  "password": "MySecret123"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(nonExistentEmailPayload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Credenciales inválidas"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(
                        jsonPath("$.detail")
                                .value("El correo electrónico o la contraseña son incorrectos"));
    }

    @Test
    @DisplayName(
            "Aplica rate limiting de 5 intentos por minuto en login, retornando HTTP 429 con"
                    + " Retry-After en el 6to intento (AUTH-REQ-27, AUTH-REQ-28)")
    void shouldEnforceRateLimitOnLoginAfterFiveAttempts() throws Exception {
        String loginPayload =
                """
                {
                  "email": "ratelimit@example.com",
                  "password": "WrongPassword"
                }
                """;

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(
                            post("/api/v1/auth/login")
                                    .with(csrf())
                                    .header("X-Forwarded-For", "203.0.113.50")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(loginPayload))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .with(csrf())
                                .header("X-Forwarded-For", "203.0.113.50")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginPayload))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.title").value("Demasiadas solicitudes"))
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    @DisplayName("Logout sin sesión activa es rechazado con HTTP 401 Unauthorized (AUTH-REQ-30)")
    void shouldRejectLogoutWhenUnauthenticatedWith401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("No autenticado"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(
                        jsonPath("$.detail")
                                .value("Debe iniciar sesión para acceder a este recurso"));
    }

    @Test
    @DisplayName(
            "Logout destruye la sesión en SPRING_SESSION, limpia la cookie SESSION y elimina"
                    + " selectivamente la suscripción push del dispositivo (AUTH-REQ-12, EC-09)")
    void shouldLogoutInvalidateSessionClearCookieAndDeleteMatchingPushSubscription()
            throws Exception {
        Long userId = registerTestUser("logout@example.com", "LogoutUser", "MySecret123");
        User user = userRepository.findById(userId).orElseThrow();

        Instant now = Instant.now();
        pushSubscriptionRepository.save(
                new PushSubscription(
                        user, "https://push.example.com/device-1", "key1", "auth1", now));
        pushSubscriptionRepository.save(
                new PushSubscription(
                        user, "https://push.example.com/device-2", "key2", "auth2", now));

        String loginPayload =
                """
                {
                  "email": "logout@example.com",
                  "password": "MySecret123"
                }
                """;

        MvcResult loginResult =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(loginPayload))
                        .andExpect(status().isOk())
                        .andReturn();

        Cookie sessionCookie = loginResult.getResponse().getCookie("SESSION");
        assertThat(sessionCookie).isNotNull();

        Integer sessionCountBefore =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Integer.class);
        assertThat(sessionCountBefore).isEqualTo(1);

        String logoutPayload =
                """
                {
                  "endpoint": "https://push.example.com/device-1"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/auth/logout")
                                .cookie(sessionCookie)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(logoutPayload))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        Integer sessionCountAfter =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Integer.class);
        assertThat(sessionCountAfter).isZero();

        assertThat(pushSubscriptionRepository.findAll())
                .extracting(PushSubscription::getEndpoint)
                .containsExactly("https://push.example.com/device-2");
    }

    @Test
    @DisplayName(
            "GET /api/v1/auth/csrf emite token y cookie XSRF-TOKEN utilizable en POST"
                    + " /api/v1/auth/login (HRD-REQ-05)")
    void shouldBootstrapCsrfTokenAndAllowLoginWithReturnedCookieAndHeader() throws Exception {
        registerTestUser("csrf@example.com", "CsrfUser", "MySecret123");
        WebTestUtils.setCsrfTokenRepository(
                new MockHttpServletRequest(context.getServletContext()), cookieCsrfTokenRepository);

        MvcResult csrfResult =
                mockMvc.perform(get("/api/v1/auth/csrf"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.token").isNotEmpty())
                        .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
                        .andExpect(jsonPath("$.parameterName").value("_csrf"))
                        .andReturn();

        Cookie xsrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrfCookie).isNotNull();
        assertThat(xsrfCookie.isHttpOnly()).isFalse();

        JsonNode csrfJson = objectMapper.readTree(csrfResult.getResponse().getContentAsString());
        String token = csrfJson.get("token").asString();

        String loginPayload =
                """
                {
                  "email": "csrf@example.com",
                  "password": "MySecret123"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .cookie(xsrfCookie)
                                .header("X-XSRF-TOKEN", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("csrf@example.com"));
    }

    @Test
    @DisplayName(
            "Login re-hashea transparentemente contraseñas cuyo algoritmo requiere actualización"
                    + " (HRD-REQ-06, FND-REQ-12)")
    void shouldTransparentlyUpgradePasswordHashOnLogin() throws Exception {
        Long userId = registerTestUser("upgrade@example.com", "UpgradeUser", "MySecret123");

        User user = userRepository.findById(userId).orElseThrow();
        user.setPasswordHash("{noop}MySecret123");
        userRepository.saveAndFlush(user);

        String loginPayload =
                """
                {
                  "email": "upgrade@example.com",
                  "password": "MySecret123"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("upgrade@example.com"));

        User upgradedUser = userRepository.findById(userId).orElseThrow();
        assertThat(upgradedUser.getPasswordHash()).startsWith("{bcrypt}");
        assertThat(passwordEncoder.matches("MySecret123", upgradedUser.getPasswordHash())).isTrue();
    }
}
