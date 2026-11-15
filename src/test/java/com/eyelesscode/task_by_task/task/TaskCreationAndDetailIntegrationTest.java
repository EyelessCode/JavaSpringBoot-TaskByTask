package com.eyelesscode.task_by_task.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eyelesscode.task_by_task.shared.AbstractIntegrationTest;
import com.eyelesscode.task_by_task.shared.ratelimit.RateLimitService;
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

class TaskCreationAndDetailIntegrationTest extends AbstractIntegrationTest {

    private static final Instant DEFAULT_NOW =
            Instant.parse("2026-11-15T12:00:00Z"); // 07:00 Bogota

    @Autowired private WebApplicationContext context;
    @Autowired private SessionRepositoryFilter<? extends Session> sessionRepositoryFilter;
    @Autowired private UserRepository userRepository;
    @Autowired private RateLimitService rateLimitService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private Clock clock;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        setClock(DEFAULT_NOW);
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

    @Test
    @DisplayName(
            "POST /groups/{id}/tasks normalizes blank description and null tags, GET returns"
                    + " detail")
    void createTaskWithNullTagsAndBlankDescription() throws Exception {
        AuthenticatedUser user =
                registerAndGetSession("task1@example.com", "taskuser1", "America/Bogota");

        String payload =
                """
                {
                  "title": "  Comprar café  ",
                  "description": "   ",
                  "tags": null
                }
                """;

        MvcResult createResult =
                mockMvc.perform(
                                post("/api/v1/groups/{groupId}/tasks", user.personalGroupId())
                                        .cookie(user.sessionCookie())
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.groupId").value(user.personalGroupId()))
                        .andExpect(jsonPath("$.assignedUserId").doesNotExist())
                        .andExpect(jsonPath("$.title").value("Comprar café"))
                        .andExpect(jsonPath("$.description").doesNotExist())
                        .andExpect(jsonPath("$.completed").value(false))
                        .andExpect(jsonPath("$.completedAt").doesNotExist())
                        .andExpect(jsonPath("$.overdue").value(false))
                        .andExpect(jsonPath("$.dueDate").doesNotExist())
                        .andExpect(jsonPath("$.dueTime").doesNotExist())
                        .andExpect(jsonPath("$.tags").isArray())
                        .andExpect(jsonPath("$.tags.length()").value(0))
                        .andReturn();

        long taskId =
                objectMapper
                        .readTree(createResult.getResponse().getContentAsString())
                        .get("id")
                        .asLong();

        assertThat(findScheduledExecutionTime(taskId)).isNull();

        mockMvc.perform(get("/api/v1/tasks/{taskId}", taskId).cookie(user.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(taskId))
                .andExpect(jsonPath("$.title").value("Comprar café"))
                .andExpect(jsonPath("$.tags.length()").value(0));
    }

    @Test
    @DisplayName(
            "POST /groups/{id}/tasks deduplicates tags case-insensitively and sorts alphabetically"
                    + " (EC-05)")
    void createTaskDeduplicatesTagsAndSortsAlphabetically() throws Exception {
        AuthenticatedUser user =
                registerAndGetSession("task2@example.com", "taskuser2", "America/Bogota");

        String payload =
                """
                {
                  "title": "Revisar PR",
                  "description": "Validar pruebas",
                  "tags": ["  Urgente ", "urgente", "Alpha"]
                }
                """;

        MvcResult createResult =
                mockMvc.perform(
                                post("/api/v1/groups/{groupId}/tasks", user.personalGroupId())
                                        .cookie(user.sessionCookie())
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.tags.length()").value(2))
                        .andExpect(jsonPath("$.tags[0].name").value("Alpha"))
                        .andExpect(jsonPath("$.tags[1].name").value("Urgente"))
                        .andReturn();

        long taskId =
                objectMapper
                        .readTree(createResult.getResponse().getContentAsString())
                        .get("id")
                        .asLong();

        mockMvc.perform(get("/api/v1/tasks/{taskId}", taskId).cookie(user.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andExpect(jsonPath("$.tags[0].name").value("Alpha"))
                .andExpect(jsonPath("$.tags[1].name").value("Urgente"));
    }

    @Test
    @DisplayName("Past due date is born overdue=true without scheduled_tasks row (EC-01)")
    void createTaskWithPastDueDateBornOverdueWithoutJob() throws Exception {
        AuthenticatedUser user =
                registerAndGetSession("past@example.com", "pastuser", "America/Bogota");

        String payload =
                """
                {
                  "title": "Tarea atrasada",
                  "dueDate": "2026-11-14"
                }
                """;

        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/groups/{groupId}/tasks", user.personalGroupId())
                                        .cookie(user.sessionCookie())
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.overdue").value(true))
                        .andReturn();

        long taskId =
                objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        assertThat(findScheduledExecutionTime(taskId)).isNull();
    }

    @Test
    @DisplayName(
            "Date-only task follows 09:00 AM schedule rule and 23:59:59.999 overdue boundary"
                    + " (EC-01)")
    void dateOnlyTaskScheduleAndEndOfDayOverdueBoundary() throws Exception {
        AuthenticatedUser user =
                registerAndGetSession("zone@example.com", "zoneuser", "America/Bogota");

        // 07:00 Bogota (before 09:00 AM) -> schedules at 09:00 Bogota (14:00Z)
        setClock(Instant.parse("2026-11-15T12:00:00Z"));
        MvcResult morningResult =
                mockMvc.perform(
                                post("/api/v1/groups/{groupId}/tasks", user.personalGroupId())
                                        .cookie(user.sessionCookie())
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "title": "Antes de las 9",
                                                  "dueDate": "2026-11-15"
                                                }
                                                """))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.overdue").value(false))
                        .andReturn();

        long morningTaskId =
                objectMapper
                        .readTree(morningResult.getResponse().getContentAsString())
                        .get("id")
                        .asLong();
        assertThat(findScheduledExecutionTime(morningTaskId))
                .isEqualTo(Instant.parse("2026-11-15T14:00:00Z"));

        // 10:30 Bogota (after 09:00 AM) -> schedules at now (15:30Z), still not overdue
        Instant afternoonNow = Instant.parse("2026-11-15T15:30:00Z");
        setClock(afternoonNow);
        MvcResult afternoonResult =
                mockMvc.perform(
                                post("/api/v1/groups/{groupId}/tasks", user.personalGroupId())
                                        .cookie(user.sessionCookie())
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "title": "Después de las 9",
                                                  "dueDate": "2026-11-15"
                                                }
                                                """))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.overdue").value(false))
                        .andReturn();

        long afternoonTaskId =
                objectMapper
                        .readTree(afternoonResult.getResponse().getContentAsString())
                        .get("id")
                        .asLong();
        assertThat(findScheduledExecutionTime(afternoonTaskId)).isEqualTo(afternoonNow);

        // 23:59:59.999 Bogota (04:59:59.999Z next day) -> still NOT overdue
        setClock(Instant.parse("2026-11-16T04:59:59.999Z"));
        mockMvc.perform(get("/api/v1/tasks/{taskId}", morningTaskId).cookie(user.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue").value(false));

        // 00:00:00.000 Bogota next day (05:00:00Z) -> NOW overdue
        setClock(Instant.parse("2026-11-16T05:00:00Z"));
        mockMvc.perform(get("/api/v1/tasks/{taskId}", morningTaskId).cookie(user.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue").value(true));
    }

    @Test
    @DisplayName("Date+time task schedules exact instant and becomes overdue after it")
    void dateTimeTaskSchedulesExactInstant() throws Exception {
        AuthenticatedUser user =
                registerAndGetSession("exact@example.com", "exactuser", "America/Bogota");

        String payload =
                """
                {
                  "title": "Reunión",
                  "dueDate": "2026-11-15",
                  "dueTime": "16:45:00"
                }
                """;

        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/groups/{groupId}/tasks", user.personalGroupId())
                                        .cookie(user.sessionCookie())
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.overdue").value(false))
                        .andReturn();

        long taskId =
                objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        assertThat(findScheduledExecutionTime(taskId))
                .isEqualTo(Instant.parse("2026-11-15T21:45:00Z"));

        setClock(Instant.parse("2026-11-15T21:45:01Z"));
        mockMvc.perform(get("/api/v1/tasks/{taskId}", taskId).cookie(user.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue").value(true));
    }

    @Test
    @DisplayName("Server-managed fields in JSON body are ignored (EC-11)")
    void createTaskIgnoresServerManagedFields() throws Exception {
        AuthenticatedUser user = registerAndGetSession("ec11@example.com", "ec11user", "UTC");

        String payload =
                """
                {
                  "id": 99999,
                  "groupId": 99999,
                  "assignedUserId": 99999,
                  "completed": true,
                  "completedAt": "2020-01-01T00:00:00Z",
                  "overdue": true,
                  "createdAt": "2020-01-01T00:00:00Z",
                  "updatedAt": "2020-01-01T00:00:00Z",
                  "title": "Segura"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/groups/{groupId}/tasks", user.personalGroupId())
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(99999)))
                .andExpect(jsonPath("$.groupId").value(user.personalGroupId()))
                .andExpect(jsonPath("$.assignedUserId").doesNotExist())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.completedAt").doesNotExist())
                .andExpect(jsonPath("$.overdue").value(false))
                .andExpect(jsonPath("$.createdAt").value(DEFAULT_NOW.toString()));
    }

    @Test
    @DisplayName(
            "Validation errors: dueTime without dueDate, blank title, null/invalid tags, empty date"
                    + " string")
    void validationErrorsReturn400() throws Exception {
        AuthenticatedUser user = registerAndGetSession("val@example.com", "valuser", "UTC");

        // dueTime without dueDate
        mockMvc.perform(
                        post("/api/v1/groups/{groupId}/tasks", user.personalGroupId())
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "title": "Sin fecha",
                                          "dueTime": "10:00:00"
                                        }
                                        """))
                .andExpect(status().isBadRequest());

        // blank title + null tag + delimiter tag
        mockMvc.perform(
                        post("/api/v1/groups/{groupId}/tasks", user.personalGroupId())
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "title": "   ",
                                          "tags": ["ok", null, "bad,tag"]
                                        }
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.invalidParams[*].name")
                                .value(
                                        org.hamcrest.Matchers.hasItems(
                                                "title", "tags[1]", "tags[2]")));

        // empty string dueDate
        mockMvc.perform(
                        post("/api/v1/groups/{groupId}/tasks", user.personalGroupId())
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "title": "Fecha vacía",
                                          "dueDate": ""
                                        }
                                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName(
            "Security and ownership precedence: 401 unauthenticated, 404 not found, 403 foreign")
    void securityAndOwnershipPrecedence() throws Exception {
        AuthenticatedUser owner = registerAndGetSession("owner@example.com", "owneruser", "UTC");
        AuthenticatedUser other = registerAndGetSession("other@example.com", "otheruser", "UTC");

        // 401 unauthenticated
        mockMvc.perform(
                        post("/api/v1/groups/{groupId}/tasks", owner.personalGroupId())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\": \"Sin sesión\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/tasks/{taskId}", 1L)).andExpect(status().isUnauthorized());

        // 404 group / task not found
        mockMvc.perform(
                        post("/api/v1/groups/{groupId}/tasks", 999999L)
                                .cookie(owner.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\": \"Grupo inexistente\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/tasks/{taskId}", 999999L).cookie(owner.sessionCookie()))
                .andExpect(status().isNotFound());

        // Create a task as owner
        MvcResult created =
                mockMvc.perform(
                                post("/api/v1/groups/{groupId}/tasks", owner.personalGroupId())
                                        .cookie(owner.sessionCookie())
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\": \"Privada\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        long taskId =
                objectMapper
                        .readTree(created.getResponse().getContentAsString())
                        .get("id")
                        .asLong();

        // 403 foreign group / task
        mockMvc.perform(
                        post("/api/v1/groups/{groupId}/tasks", owner.personalGroupId())
                                .cookie(other.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\": \"Ajena\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/tasks/{taskId}", taskId).cookie(other.sessionCookie()))
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
