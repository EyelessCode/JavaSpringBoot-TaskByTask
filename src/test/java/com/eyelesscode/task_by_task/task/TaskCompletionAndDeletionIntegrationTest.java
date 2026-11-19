package com.eyelesscode.task_by_task.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eyelesscode.task_by_task.shared.AbstractIntegrationTest;
import com.eyelesscode.task_by_task.shared.ratelimit.RateLimitService;
import com.eyelesscode.task_by_task.tag.TagRepository;
import com.eyelesscode.task_by_task.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class TaskCompletionAndDeletionIntegrationTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-11-15T12:00:00Z"); // 07:00 Bogota
    private static final Instant T1 = Instant.parse("2026-11-15T13:00:00Z"); // 08:00 Bogota
    private static final Instant T2 = Instant.parse("2026-11-17T12:00:00Z"); // after Nov 16

    @Autowired private WebApplicationContext context;
    @Autowired private SessionRepositoryFilter<? extends Session> sessionRepositoryFilter;
    @Autowired private UserRepository userRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private RateLimitService rateLimitService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private Clock clock;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        setClock(T0);
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

    private void setClock(Instant instant) {
        lenient().when(clock.instant()).thenReturn(instant);
        lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private record AuthenticatedUser(Long userId, Long personalGroupId, Cookie sessionCookie) {}

    private AuthenticatedUser registerAndGetSession(String email, String username, String timezone)
            throws Exception {
        String payload =
                String.format(
                        """
                        {
                          "email": "%s",
                          "username": "%s",
                          "displayName": "Test User",
                          "password": "Password123",
                          "timezone": "%s"
                        }
                        """,
                        email, username, timezone);

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

        MvcResult groupsResult =
                mockMvc.perform(get("/api/v1/groups").cookie(sessionCookie))
                        .andExpect(status().isOk())
                        .andReturn();
        long personalGroupId =
                objectMapper
                        .readTree(groupsResult.getResponse().getContentAsString())
                        .get("content")
                        .get(0)
                        .get("id")
                        .asLong();

        return new AuthenticatedUser(userId, personalGroupId, sessionCookie);
    }

    private long createGroup(Cookie cookie, String name) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/groups")
                                        .cookie(cookie)
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\": \"" + name + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private JsonNode createTask(Cookie cookie, long groupId, String jsonPayload) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/groups/{groupId}/tasks", groupId)
                                        .cookie(cookie)
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(jsonPayload))
                        .andExpect(status().isCreated())
                        .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("PATCH /completion transitions, idempotency, EC-02 overdue/scheduling, and 400")
    void completionTransitionsAndIdempotency() throws Exception {
        AuthenticatedUser user =
                registerAndGetSession("comp@example.com", "compuser", "America/Bogota");

        long task1Id =
                createTask(
                                user.sessionCookie(),
                                user.personalGroupId(),
                                """
                                {
                                  "title": "Tarea principal",
                                  "dueDate": "2026-11-16",
                                  "dueTime": "10:00:00"
                                }
                                """)
                        .get("id")
                        .asLong();

        long otherTaskId =
                createTask(
                                user.sessionCookie(),
                                user.personalGroupId(),
                                """
                                {
                                  "title": "Otra tarea",
                                  "dueDate": "2026-11-18"
                                }
                                """)
                        .get("id")
                        .asLong();

        assertThat(findScheduledExecutionTime(task1Id)).isNotNull();
        assertThat(findScheduledExecutionTime(otherTaskId)).isNotNull();

        // 400 when completed is missing or null
        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}/completion", task1Id)
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}/completion", task1Id)
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"completed\": null}"))
                .andExpect(status().isBadRequest());

        // false -> true cancels scheduled_tasks for task1 only
        setClock(T1);
        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}/completion", task1Id)
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"completed\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.completedAt").value(T1.toString()))
                .andExpect(jsonPath("$.overdue").value(false));

        assertThat(findScheduledExecutionTime(task1Id)).isNull();
        assertThat(findScheduledExecutionTime(otherTaskId)).isNotNull();

        // Idempotent true -> true preserves completedAt even when clock advances past due date
        setClock(T2);
        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}/completion", task1Id)
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"completed\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.completedAt").value(T1.toString()))
                .andExpect(jsonPath("$.overdue").value(false));

        // true -> false when now is BEFORE due date -> reschedules job and overdue=false
        setClock(T1);
        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}/completion", task1Id)
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"completed\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.completedAt").doesNotExist())
                .andExpect(jsonPath("$.overdue").value(false));

        assertThat(findScheduledExecutionTime(task1Id))
                .isEqualTo(Instant.parse("2026-11-16T15:00:00Z"));

        // Complete again, then advance past due date, then unmark -> overdue=true, no job scheduled
        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}/completion", task1Id)
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"completed\": true}"))
                .andExpect(status().isOk());

        setClock(T2);
        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}/completion", task1Id)
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"completed\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.overdue").value(true));

        assertThat(findScheduledExecutionTime(task1Id)).isNull();
    }

    @Test
    @DisplayName(
            "DELETE /tasks/{taskId} removes task and its scheduled job while preserving tags and"
                    + " other jobs")
    void deleteTaskSelectivelyCleansUpAndPreservesTags() throws Exception {
        AuthenticatedUser user = registerAndGetSession("del@example.com", "deluser", "UTC");

        long taskToDelete =
                createTask(
                                user.sessionCookie(),
                                user.personalGroupId(),
                                """
                                {
                                  "title": "Eliminar",
                                  "dueDate": "2026-11-18",
                                  "tags": ["Persistente"]
                                }
                                """)
                        .get("id")
                        .asLong();

        long survivingTask =
                createTask(
                                user.sessionCookie(),
                                user.personalGroupId(),
                                """
                                {
                                  "title": "Sobrevive",
                                  "dueDate": "2026-11-19"
                                }
                                """)
                        .get("id")
                        .asLong();

        mockMvc.perform(
                        delete("/api/v1/tasks/{taskId}", taskToDelete)
                                .cookie(user.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/tasks/{taskId}", taskToDelete).cookie(user.sessionCookie()))
                .andExpect(status().isNotFound());

        assertThat(findScheduledExecutionTime(taskToDelete)).isNull();
        assertThat(findScheduledExecutionTime(survivingTask)).isNotNull();
        assertThat(
                        tagRepository.findByGroupIdAndNormalizedName(
                                user.personalGroupId(), "persistente"))
                .isPresent();
    }

    @Test
    @DisplayName(
            "DELETE /tasks/{taskId}/tags/{tagId} enforces EC-06 precedence and preserves tag"
                    + " catalog")
    void detachTagPrecedenceMatrix() throws Exception {
        AuthenticatedUser owner = registerAndGetSession("detowner@example.com", "detowner", "UTC");
        AuthenticatedUser other = registerAndGetSession("detother@example.com", "detother", "UTC");

        long secondGroupId = createGroup(owner.sessionCookie(), "Trabajo");
        JsonNode ownerTask =
                createTask(
                        owner.sessionCookie(),
                        owner.personalGroupId(),
                        "{\"title\": \"Con tag\", \"tags\": [\"PersonalTag\"]}");
        long ownerTaskId = ownerTask.get("id").asLong();
        long personalTagId = ownerTask.get("tags").get(0).get("id").asLong();

        JsonNode secondGroupTask =
                createTask(
                        owner.sessionCookie(),
                        secondGroupId,
                        "{\"title\": \"Otro grupo\", \"tags\": [\"WorkTag\"]}");
        long workTagId = secondGroupTask.get("tags").get(0).get("id").asLong();

        JsonNode otherUserTask =
                createTask(
                        other.sessionCookie(),
                        other.personalGroupId(),
                        "{\"title\": \"Ajena\", \"tags\": [\"OtherTag\"]}");
        long otherTaskId = otherUserTask.get("id").asLong();
        long otherTagId = otherUserTask.get("tags").get(0).get("id").asLong();

        // 401 unauthenticated
        mockMvc.perform(
                        delete("/api/v1/tasks/{taskId}/tags/{tagId}", ownerTaskId, personalTagId)
                                .with(csrf()))
                .andExpect(status().isUnauthorized());

        // 404 task not found
        mockMvc.perform(
                        delete("/api/v1/tasks/{taskId}/tags/{tagId}", 999999L, personalTagId)
                                .cookie(owner.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Tarea no encontrada"));

        // 403 foreign task (even with non-existent tagId)
        mockMvc.perform(
                        delete("/api/v1/tasks/{taskId}/tags/{tagId}", otherTaskId, 999999L)
                                .cookie(owner.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isForbidden());

        // 404 tag not found on owned task
        mockMvc.perform(
                        delete("/api/v1/tasks/{taskId}/tags/{tagId}", ownerTaskId, 999999L)
                                .cookie(owner.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Etiqueta no encontrada"));

        // 403 foreign tag on owned task
        mockMvc.perform(
                        delete("/api/v1/tasks/{taskId}/tags/{tagId}", ownerTaskId, otherTagId)
                                .cookie(owner.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isForbidden());

        // 404 own tag in different group (not associated to task)
        mockMvc.perform(
                        delete("/api/v1/tasks/{taskId}/tags/{tagId}", ownerTaskId, workTagId)
                                .cookie(owner.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("La etiqueta no está asociada a esta tarea"));

        // 204 valid detach, preserves tag in catalog
        mockMvc.perform(
                        delete("/api/v1/tasks/{taskId}/tags/{tagId}", ownerTaskId, personalTagId)
                                .cookie(owner.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/tasks/{taskId}", ownerTaskId).cookie(owner.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(0));

        assertThat(tagRepository.findById(personalTagId)).isPresent();

        // Second detach returns 404 not associated
        mockMvc.perform(
                        delete("/api/v1/tasks/{taskId}/tags/{tagId}", ownerTaskId, personalTagId)
                                .cookie(owner.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("La etiqueta no está asociada a esta tarea"));
    }

    @Test
    @DisplayName("Security and ownership precedence on completion and deletion: 401, 404, 403")
    void completionAndDeleteSecurityPrecedence() throws Exception {
        AuthenticatedUser owner = registerAndGetSession("secowner@example.com", "secowner", "UTC");
        AuthenticatedUser other = registerAndGetSession("secother@example.com", "secother", "UTC");

        long taskId =
                createTask(
                                owner.sessionCookie(),
                                owner.personalGroupId(),
                                "{\"title\": \"Propia\"}")
                        .get("id")
                        .asLong();

        // 401
        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}/completion", taskId)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"completed\": true}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/tasks/{taskId}", taskId).with(csrf()))
                .andExpect(status().isUnauthorized());

        // 404
        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}/completion", 999999L)
                                .cookie(owner.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"completed\": true}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        delete("/api/v1/tasks/{taskId}", 999999L)
                                .cookie(owner.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNotFound());

        // 403
        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}/completion", taskId)
                                .cookie(other.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"completed\": true}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        delete("/api/v1/tasks/{taskId}", taskId)
                                .cookie(other.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    private Instant findScheduledExecutionTime(Long taskId) {
        List<Timestamp> rows =
                jdbcTemplate.queryForList(
                        "SELECT execution_time FROM scheduled_tasks WHERE task_name = ? AND"
                                + " task_instance = ?",
                        Timestamp.class,
                        TaskDueSchedulePort.TASK_NAME,
                        TaskDueSchedulePort.INSTANCE_PREFIX + taskId);
        return rows.isEmpty() ? null : rows.getFirst().toInstant();
    }
}
