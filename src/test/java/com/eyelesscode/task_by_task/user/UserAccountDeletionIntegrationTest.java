package com.eyelesscode.task_by_task.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eyelesscode.task_by_task.group.Group;
import com.eyelesscode.task_by_task.group.GroupRepository;
import com.eyelesscode.task_by_task.notification.PushSubscription;
import com.eyelesscode.task_by_task.notification.PushSubscriptionRepository;
import com.eyelesscode.task_by_task.shared.AbstractIntegrationTest;
import com.eyelesscode.task_by_task.shared.ratelimit.RateLimitService;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
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

class UserAccountDeletionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private SessionRepositoryFilter<? extends Session> sessionRepositoryFilter;
    @Autowired private UserRepository userRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private PushSubscriptionRepository pushSubscriptionRepository;
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
        jdbcTemplate.execute("DELETE FROM scheduled_tasks");
        jdbcTemplate.execute("DELETE FROM SPRING_SESSION");
        userRepository.deleteAll();
    }

    private record RegisteredUserSession(Long userId, Cookie sessionCookie) {}

    private RegisteredUserSession registerUser(String email, String username, String password)
            throws Exception {
        String payload =
                String.format(
                        """
                        {
                          "email": "%s",
                          "username": "%s",
                          "displayName": "Delete Tester",
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

        return new RegisteredUserSession(userId, sessionCookie);
    }

    @Test
    @DisplayName(
            "Rechaza acceso sin sesión a DELETE /api/v1/users/me con HTTP 401 RFC 7807"
                    + " (AUTH-REQ-30)")
    void shouldRejectUnauthenticatedDeleteAccountWith401() throws Exception {
        mockMvc.perform(
                        delete("/api/v1/users/me")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\": \"AnyPass123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("No autenticado"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName(
            "Rechaza eliminación de cuenta con HTTP 400 si la contraseña es incorrecta, preservando"
                    + " la cuenta y todos sus datos intactos (AUTH-REQ-26, EC-10)")
    void shouldRejectDeleteAccountWithWrongPasswordAndPreserveData() throws Exception {
        RegisteredUserSession fixture =
                registerUser("keepme@example.com", "KeepMeUser", "CorrectPass123");

        mockMvc.perform(
                        delete("/api/v1/users/me")
                                .cookie(fixture.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\": \"WrongPassword99\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Contraseña incorrecta"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("La contraseña ingresada es incorrecta"));

        assertThat(userRepository.findById(fixture.userId())).isPresent();
        assertThat(groupRepository.findByCreatorIdAndIsPersonalTrue(fixture.userId())).isPresent();
    }

    @Test
    @DisplayName(
            "Eliminación definitiva de cuenta borra en cascada usuario, grupos, membresías,"
                    + " tareas, etiquetas, códigos, suscripciones push, notificaciones, sesiones y"
                    + " cancela jobs en scheduled_tasks (AUTH-REQ-25, EC-08)")
    void shouldDeleteAccountAndCascadeAllAssociatedDataAndScheduledJobs() throws Exception {
        RegisteredUserSession toDelete =
                registerUser("delete@example.com", "DeleteUser", "SecretPass123");
        RegisteredUserSession survivor =
                registerUser("survivor@example.com", "SurvivorUser", "SecretPass123");

        User userToDelete = userRepository.findById(toDelete.userId()).orElseThrow();
        Group personalGroup =
                groupRepository.findByCreatorIdAndIsPersonalTrue(toDelete.userId()).orElseThrow();
        Group survivorGroup =
                groupRepository.findByCreatorIdAndIsPersonalTrue(survivor.userId()).orElseThrow();

        // Insertar suscripción push y notificación para el usuario a eliminar
        pushSubscriptionRepository.save(
                new PushSubscription(
                        userToDelete,
                        "https://push.example.com/del-device",
                        "p256dh",
                        "auth",
                        Instant.now()));
        jdbcTemplate.update(
                "INSERT INTO notifications (user_id, event_type, content, is_read) VALUES (?,"
                        + " 'TASK_DUE', 'Tarea pendiente', FALSE)",
                toDelete.userId());

        // Insertar tarea, etiqueta, relación task_tags y job en scheduled_tasks para el usuario a
        // eliminar
        Long deletedTaskId =
                jdbcTemplate.queryForObject(
                        "INSERT INTO tasks (group_id, assigned_user_id, title, description,"
                                + " completed) VALUES (?, ?, 'Tarea a borrar', 'Desc', FALSE)"
                                + " RETURNING id",
                        Long.class,
                        personalGroup.getId(),
                        toDelete.userId());
        Long deletedTagId =
                jdbcTemplate.queryForObject(
                        "INSERT INTO tags (group_id, name) VALUES (?, 'urgente') RETURNING id",
                        Long.class,
                        personalGroup.getId());
        jdbcTemplate.update(
                "INSERT INTO task_tags (task_id, tag_id) VALUES (?, ?)",
                deletedTaskId,
                deletedTagId);
        jdbcTemplate.update(
                "INSERT INTO scheduled_tasks (task_name, task_instance, execution_time, picked,"
                        + " version) VALUES ('task-due-notification', ?, CURRENT_TIMESTAMP, FALSE,"
                        + " 1)",
                "task-due-" + deletedTaskId);

        // Insertar tarea y job en scheduled_tasks para el otro usuario (survivor) que NO debe
        // tocarse
        Long survivorTaskId =
                jdbcTemplate.queryForObject(
                        "INSERT INTO tasks (group_id, title, completed) VALUES (?, 'Tarea"
                                + " sobreviviente', FALSE) RETURNING id",
                        Long.class,
                        survivorGroup.getId());
        jdbcTemplate.update(
                "INSERT INTO scheduled_tasks (task_name, task_instance, execution_time, picked,"
                        + " version) VALUES ('task-due-notification', ?, CURRENT_TIMESTAMP, FALSE,"
                        + " 1)",
                "task-due-" + survivorTaskId);

        // Crear una segunda sesión para el usuario a eliminar
        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "email": "delete@example.com",
                                          "password": "SecretPass123"
                                        }
                                        """))
                .andExpect(status().isOk());

        // Ejecutar DELETE /api/v1/users/me
        mockMvc.perform(
                        delete("/api/v1/users/me")
                                .cookie(toDelete.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\": \"SecretPass123\"}"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        // Verificar que la cuenta y toda su cascada fueron eliminadas
        assertThat(userRepository.findById(toDelete.userId())).isEmpty();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM groups WHERE creator_id = ?",
                                Integer.class,
                                toDelete.userId()))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM group_memberships WHERE user_id = ?",
                                Integer.class,
                                toDelete.userId()))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM tasks WHERE id = ?",
                                Integer.class,
                                deletedTaskId))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM tags WHERE id = ?",
                                Integer.class,
                                deletedTagId))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM task_tags WHERE task_id = ?",
                                Integer.class,
                                deletedTaskId))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM recovery_codes WHERE user_id = ?",
                                Integer.class,
                                toDelete.userId()))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM push_subscriptions WHERE user_id = ?",
                                Integer.class,
                                toDelete.userId()))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM notifications WHERE user_id = ?",
                                Integer.class,
                                toDelete.userId()))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?",
                                Integer.class,
                                String.valueOf(toDelete.userId())))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM scheduled_tasks WHERE task_instance = ?",
                                Integer.class,
                                "task-due-" + deletedTaskId))
                .isZero();

        // Verificar que los datos y jobs del otro usuario siguen intactos
        assertThat(userRepository.findById(survivor.userId())).isPresent();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM scheduled_tasks WHERE task_instance = ?",
                                Integer.class,
                                "task-due-" + survivorTaskId))
                .isEqualTo(1);
    }
}
