package com.eyelesscode.task_by_task.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

class TaskUpdateIntegrationTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-11-15T12:00:00Z"); // 07:00 Bogota
    private static final Instant T1 = Instant.parse("2026-11-15T13:00:00Z"); // 08:00 Bogota

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

    private long createTask(AuthenticatedUser user, String jsonPayload) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/groups/{groupId}/tasks", user.personalGroupId())
                                        .cookie(user.sessionCookie())
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(jsonPayload))
                        .andExpect(status().isCreated())
                        .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    @DisplayName("PATCH {} is inert: preserves updatedAt, tags, and scheduled_tasks (TSK-REQ-07)")
    void emptyPatchHasNoSideEffects() throws Exception {
        AuthenticatedUser user =
                registerAndGetSession("inert@example.com", "inertuser", "America/Bogota");

        long taskId =
                createTask(
                        user,
                        """
                        {
                          "title": "Original",
                          "description": "Detalle",
                          "dueDate": "2026-11-16",
                          "dueTime": "10:00:00",
                          "tags": ["Trabajo"]
                        }
                        """);

        Instant scheduledBefore = findScheduledExecutionTime(taskId);
        assertThat(scheduledBefore).isEqualTo(Instant.parse("2026-11-16T15:00:00Z"));

        setClock(T1);

        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}", taskId)
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Original"))
                .andExpect(jsonPath("$.description").value("Detalle"))
                .andExpect(jsonPath("$.updatedAt").value(T0.toString()))
                .andExpect(jsonPath("$.tags.length()").value(1));

        assertThat(findScheduledExecutionTime(taskId)).isEqualTo(scheduledBefore);
    }

    @Test
    @DisplayName(
            "PATCH clears dueDate+dueTime together (EC-03) and preserves group tags on detach"
                    + " (EC-04)")
    void explicitNullClearsDueDateAndTimeAndPreservesGroupTags() throws Exception {
        AuthenticatedUser user =
                registerAndGetSession("ec03@example.com", "ec03user", "America/Bogota");

        long taskId =
                createTask(
                        user,
                        """
                        {
                          "title": "Con fecha",
                          "description": "Algo",
                          "dueDate": "2026-11-16",
                          "dueTime": "10:00:00",
                          "tags": ["Casa", "Urgente"]
                        }
                        """);

        assertThat(findScheduledExecutionTime(taskId)).isNotNull();

        setClock(T1);

        // Clear dueTime only -> keeps dueDate and reschedules to 09:00 Bogota (14:00Z)
        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}", taskId)
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"dueTime\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDate").value("2026-11-16"))
                .andExpect(jsonPath("$.dueTime").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").value(T1.toString()));

        assertThat(findScheduledExecutionTime(taskId))
                .isEqualTo(Instant.parse("2026-11-16T14:00:00Z"));

        // Clear dueDate with omitted dueTime -> clears both and cancels scheduled_tasks (EC-03)
        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}", taskId)
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"dueDate\": null, \"description\": \"   \", \"tags\":"
                                                + " null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDate").doesNotExist())
                .andExpect(jsonPath("$.dueTime").doesNotExist())
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.tags.length()").value(0));

        assertThat(findScheduledExecutionTime(taskId)).isNull();
        // Tags remain in group catalog (EC-04)
        assertThat(tagRepository.findByGroupIdAndNormalizedName(user.personalGroupId(), "casa"))
                .isPresent();
        assertThat(tagRepository.findByGroupIdAndNormalizedName(user.personalGroupId(), "urgente"))
                .isPresent();
    }

    @Test
    @DisplayName(
            "PATCH replaces tags keeping existing links, deduplicating (EC-05), and preserving"
                    + " catalog")
    void replaceTagsKeepsSharedTagsAndDeduplicates() throws Exception {
        AuthenticatedUser user = registerAndGetSession("tags@example.com", "tagsuser", "UTC");

        long taskId =
                createTask(
                        user,
                        """
                        {
                          "title": "Etiquetas",
                          "tags": ["Keep", "Remove"]
                        }
                        """);

        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}", taskId)
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"tags\": [\"keep\", \"Added\", \"added\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andExpect(jsonPath("$.tags[0].name").value("Added"))
                .andExpect(jsonPath("$.tags[1].name").value("Keep"));

        assertThat(tagRepository.findByGroupIdAndNormalizedName(user.personalGroupId(), "remove"))
                .isPresent();
    }

    @Test
    @DisplayName("PATCH on completed task never schedules job and keeps overdue=false (EC-02)")
    void patchCompletedTaskNeverSchedulesJobAndKeepsOverdueFalse() throws Exception {
        AuthenticatedUser user =
                registerAndGetSession("ec02@example.com", "ec02user", "America/Bogota");

        long taskId = createTask(user, "{\"title\": \"Completada\"}");
        jdbcTemplate.update(
                "UPDATE tasks SET completed = true, completed_at = ? WHERE id = ?",
                Timestamp.from(T0),
                taskId);

        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}", taskId)
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"dueDate\": \"2026-11-10\", \"dueTime\": \"08:00:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.overdue").value(false));

        assertThat(findScheduledExecutionTime(taskId)).isNull();
    }

    @Test
    @DisplayName("PATCH rejects invalid fields, dueTime without dueDate, and ignores EC-11 fields")
    void patchValidationAndEc11() throws Exception {
        AuthenticatedUser user = registerAndGetSession("valpatch@example.com", "valpatch", "UTC");

        long taskId = createTask(user, "{\"title\": \"Válida\"}");

        // dueTime without dueDate -> 400
        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}", taskId)
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"dueTime\": \"12:00:00\"}"))
                .andExpect(status().isBadRequest());

        // invalid title + tags[1] -> 400 invalidParams
        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}", taskId)
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\": \" \", \"tags\": [\"ok\", null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.invalidParams[*].name")
                                .value(org.hamcrest.Matchers.hasItems("title", "tags[1]")));

        // EC-11 server-managed fields ignored
        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}", taskId)
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "id": 9999,
                                          "groupId": 9999,
                                          "assignedUserId": 9999,
                                          "completed": true,
                                          "overdue": true,
                                          "title": "Actualizada"
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(taskId))
                .andExpect(jsonPath("$.groupId").value(user.personalGroupId()))
                .andExpect(jsonPath("$.assignedUserId").doesNotExist())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.overdue").value(false))
                .andExpect(jsonPath("$.title").value("Actualizada"));
    }

    @Test
    @DisplayName(
            "Concurrent PATCH updates serialize via pessimistic lock without 500 errors (EC-10)")
    void concurrentPatchUpdatesSerializeCleanly() throws Exception {
        AuthenticatedUser user = registerAndGetSession("conc@example.com", "concuser", "UTC");
        long taskId = createTask(user, "{\"title\": \"Inicial\"}");

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> callables = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            int idx = i;
            callables.add(
                    () -> {
                        ready.countDown();
                        start.await();
                        MvcResult res =
                                mockMvc.perform(
                                                patch("/api/v1/tasks/{taskId}", taskId)
                                                        .cookie(user.sessionCookie())
                                                        .with(csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                "{\"title\": \"Concurrencia "
                                                                        + idx
                                                                        + "\", \"tags\":"
                                                                        + " [\"SharedTag\"]}"))
                                        .andReturn();
                        return res.getResponse().getStatus();
                    });
        }

        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> c : callables) {
            futures.add(executor.submit(c));
        }
        ready.await();
        start.countDown();

        for (Future<Integer> f : futures) {
            assertThat(f.get()).isEqualTo(200);
        }
        executor.shutdown();

        mockMvc.perform(get("/api/v1/tasks/{taskId}", taskId).cookie(user.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(1))
                .andExpect(jsonPath("$.tags[0].name").value("SharedTag"));
    }

    @Test
    @DisplayName("Security and ownership precedence on PATCH: 401, 404, 403")
    void patchSecurityAndOwnership() throws Exception {
        AuthenticatedUser owner =
                registerAndGetSession("patchowner@example.com", "patchowner", "UTC");
        AuthenticatedUser other =
                registerAndGetSession("patchother@example.com", "patchother", "UTC");

        long taskId = createTask(owner, "{\"title\": \"Propia\"}");

        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}", taskId)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}", 999999L)
                                .cookie(owner.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}", taskId)
                                .cookie(other.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
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
