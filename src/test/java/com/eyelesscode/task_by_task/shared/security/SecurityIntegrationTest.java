package com.eyelesscode.task_by_task.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eyelesscode.task_by_task.shared.AbstractIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

class SecurityIntegrationTest extends AbstractIntegrationTest {

    @Autowired private WebApplicationContext context;

    private MockMvc mockMvc;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private PasswordEncoder passwordEncoder;

    @Autowired private SessionRepository<? extends Session> sessionRepository;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @RestController
    @RequestMapping("/api/test-security")
    static class TestSecurityController {

        @GetMapping("/protected")
        String protectedGet() {
            return "ok-get";
        }

        @PostMapping("/protected")
        String protectedPost() {
            return "ok-post";
        }

        @PutMapping("/protected")
        String protectedPut() {
            return "ok-put";
        }

        @DeleteMapping("/protected")
        String protectedDelete() {
            return "ok-delete";
        }

        @GetMapping("/admin-only")
        @PreAuthorize("hasRole('ADMIN')")
        String adminOnly() {
            return "ok-admin";
        }
    }

    @Nested
    @DisplayName("Autenticación y rutas públicas (FND-REQ-16, FND-REQ-19)")
    class AuthenticationAndPublicRoutesTests {

        @Test
        @DisplayName(
                "Petición no autenticada a ruta protegida retorna HTTP 401 ProblemDetail en español"
                        + " (FND-REQ-16)")
        void shouldRejectUnauthenticatedRequestWithProblemDetail401() throws Exception {
            mockMvc.perform(get("/api/test-security/protected"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(
                            header().string(
                                            HttpHeaders.CONTENT_TYPE,
                                            containsString("application/problem+json")))
                    .andExpect(jsonPath("$.title").value("No autenticado"))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(
                            jsonPath("$.detail")
                                    .value("Debe iniciar sesión para acceder a este recurso"))
                    .andExpect(jsonPath("$.instance").value("/api/test-security/protected"));
        }

        @Test
        @DisplayName("Endpoint de salud /actuator/health es accesible públicamente sin sesión")
        void shouldAllowPublicHealthEndpointWithoutSession() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        }
    }

    @Nested
    @DisplayName("Protección Anti-CSRF (FND-REQ-11)")
    class CsrfProtectionTests {

        @Test
        @DisplayName(
                "Peticiones mutables sin token CSRF son rechazadas con HTTP 403 ProblemDetail en"
                        + " español")
        void shouldRejectMutatingRequestsWithoutCsrfToken() throws Exception {
            mockMvc.perform(post("/api/test-security/protected").with(user("alice").roles("USER")))
                    .andExpect(status().isForbidden())
                    .andExpect(
                            header().string(
                                            HttpHeaders.CONTENT_TYPE,
                                            containsString("application/problem+json")))
                    .andExpect(jsonPath("$.title").value("Acceso denegado"))
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.detail").value(containsString("CSRF")));
        }

        @Test
        @DisplayName(
                "Peticiones mutables con token CSRF válido son procesadas exitosamente (POST, PUT,"
                        + " DELETE)")
        void shouldAllowMutatingRequestsWithValidCsrfToken() throws Exception {
            mockMvc.perform(
                            post("/api/test-security/protected")
                                    .with(user("alice").roles("USER"))
                                    .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(content().string("ok-post"));

            mockMvc.perform(
                            put("/api/test-security/protected")
                                    .with(user("alice").roles("USER"))
                                    .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(content().string("ok-put"));

            mockMvc.perform(
                            delete("/api/test-security/protected")
                                    .with(user("alice").roles("USER"))
                                    .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(content().string("ok-delete"));
        }
    }

    @Nested
    @DisplayName("Autorización basada en roles (FND-REQ-19)")
    class AuthorizationTests {

        @Test
        @DisplayName(
                "Usuario autenticado sin el rol requerido recibe HTTP 403 ProblemDetail en español"
                        + " (FND-REQ-19)")
        void shouldRejectWhenUserLacksRequiredRole() throws Exception {
            mockMvc.perform(get("/api/test-security/admin-only").with(user("bob").roles("VISITOR")))
                    .andExpect(status().isForbidden())
                    .andExpect(
                            header().string(
                                            HttpHeaders.CONTENT_TYPE,
                                            containsString("application/problem+json")))
                    .andExpect(jsonPath("$.title").value("Acceso denegado"))
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(
                            jsonPath("$.detail")
                                    .value("No tiene permisos para realizar esta acción"))
                    .andExpect(jsonPath("$.instance").value("/api/test-security/admin-only"));
        }

        @Test
        @DisplayName("Usuario con el rol requerido accede exitosamente")
        void shouldAllowAccessWhenUserHasRequiredRole() throws Exception {
            mockMvc.perform(
                            get("/api/test-security/admin-only")
                                    .with(user("adminUser").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(content().string("ok-admin"));
        }
    }

    @Nested
    @DisplayName(
            "Persistencia de sesiones en PostgreSQL (FND-REQ-09, FND-REQ-10, FND-REQ-13, EC-05)")
    class SessionPersistenceTests {

        @Test
        @DisplayName(
                "Las sesiones se persisten en la tabla SPRING_SESSION con timeout de 7 días y se"
                        + " eliminan de BD")
        void shouldPersistAndInvalidateSessionInPostgres() {
            @SuppressWarnings("unchecked")
            SessionRepository<Session> repo = (SessionRepository<Session>) sessionRepository;

            Session session = repo.createSession();
            assertThat(session.getMaxInactiveInterval()).isEqualTo(Duration.ofSeconds(604800));

            repo.save(session);

            Integer count =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM SPRING_SESSION WHERE SESSION_ID = ?",
                            Integer.class,
                            session.getId());
            assertThat(count).isEqualTo(1);

            // Invalida la sesión (logout o cuenta borrada)
            repo.deleteById(session.getId());

            Integer countAfterDelete =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM SPRING_SESSION WHERE SESSION_ID = ?",
                            Integer.class,
                            session.getId());
            assertThat(countAfterDelete).isZero();
        }
    }

    @Nested
    @DisplayName("Criptografía de contraseñas y códigos de recuperación (FND-REQ-12)")
    class PasswordEncoderTests {

        @Test
        @DisplayName(
                "PasswordEncoder genera hashes con prefijo {bcrypt} y verifica contraseñas y"
                        + " códigos")
        void shouldHashWithBcryptAndVerifyCorrectly() {
            String rawPassword = "StrongP@ssw0rd2026";
            String hashedPassword = passwordEncoder.encode(rawPassword);

            assertThat(hashedPassword).startsWith("{bcrypt}");
            assertThat(passwordEncoder.matches(rawPassword, hashedPassword)).isTrue();
            assertThat(passwordEncoder.matches("WrongPassword", hashedPassword)).isFalse();

            // Código de recuperación de un solo uso
            String recoveryCode = "A1B2-C3D4";
            String hashedRecovery = passwordEncoder.encode(recoveryCode);

            assertThat(hashedRecovery).startsWith("{bcrypt}");
            assertThat(passwordEncoder.matches(recoveryCode, hashedRecovery)).isTrue();
            assertThat(passwordEncoder.matches("X9Y8-Z7W6", hashedRecovery)).isFalse();
        }
    }
}
