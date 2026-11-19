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

class TaskMoveAndCopyIntegrationTest extends AbstractIntegrationTest {

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
    @DisplayName(
            "POST /move: idempotent in same group; preserves createdAt/scheduled_tasks and resolves"
                    + " tags in target group (EC-07)")
    void moveTaskSameAndDifferentGroup() throws Exception {
        AuthenticatedUser user =
                registerAndGetSession("move@example.com", "moveuser", "America/Bogota");
        long targetGroupId = createGroup(user.sessionCookie(), "Trabajo");

        // Pre-create lowercase "urgente" in targetGroup to test case-insensitive reuse
        mockMvc.perform(
                        post("/api/v1/groups/{groupId}/tags", targetGroupId)
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"urgente\"}"))
                .andExpect(status().isCreated());

        long taskId =
                createTask(
                                user.sessionCookie(),
                                user.personalGroupId(),
                                """
                                {
                                  "title": "Moverme",
                                  "description": "Con etiquetas",
                                  "dueDate": "2026-11-16",
                                  "dueTime": "10:00:00",
                                  "tags": ["Urgente", "Nueva"]
                                }
                                """)
                        .get("id")
                        .asLong();

        Instant scheduledBefore = findScheduledExecutionTime(taskId);
        assertThat(scheduledBefore).isEqualTo(Instant.parse("2026-11-16T15:00:00Z"));

        setClock(T1);

        // Move to same group -> idempotent no-op
        mockMvc.perform(
                        post("/api/v1/tasks/{taskId}/move", taskId)
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"targetGroupId\": " + user.personalGroupId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(user.personalGroupId()))
                .andExpect(jsonPath("$.updatedAt").value(T0.toString()));

        // Move to targetGroup -> preserves id, createdAt, scheduled_tasks; resolves tags in target
        mockMvc.perform(
                        post("/api/v1/tasks/{taskId}/move", taskId)
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"targetGroupId\": " + targetGroupId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(taskId))
                .andExpect(jsonPath("$.groupId").value(targetGroupId))
                .andExpect(jsonPath("$.createdAt").value(T0.toString()))
                .andExpect(jsonPath("$.updatedAt").value(T1.toString()))
                .andExpect(jsonPath("$.dueDate").value("2026-11-16"))
                .andExpect(jsonPath("$.dueTime").value("10:00:00"))
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andExpect(jsonPath("$.tags[0].name").value("Nueva"))
                .andExpect(jsonPath("$.tags[0].groupId").value(targetGroupId))
                .andExpect(jsonPath("$.tags[1].name").value("urgente"))
                .andExpect(jsonPath("$.tags[1].groupId").value(targetGroupId));

        assertThat(findScheduledExecutionTime(taskId)).isEqualTo(scheduledBefore);
        // Source group tag catalog remains intact (EC-07)
        assertThat(tagRepository.findByGroupIdAndNormalizedName(user.personalGroupId(), "urgente"))
                .isPresent();
        assertThat(tagRepository.findByGroupIdAndNormalizedName(user.personalGroupId(), "nueva"))
                .isPresent();
    }

    @Test
    @DisplayName(
            "POST /copy: duplicates task to same/different group with new id/createdAt and"
                    + " conditional scheduling")
    void copyTaskSameAndDifferentGroupAndCompletedCopy() throws Exception {
        AuthenticatedUser user =
                registerAndGetSession("copy@example.com", "copyuser", "America/Bogota");
        long targetGroupId = createGroup(user.sessionCookie(), "Proyectos");

        long pendingSourceId =
                createTask(
                                user.sessionCookie(),
                                user.personalGroupId(),
                                """
                                {
                                  "title": "Pendiente",
                                  "description": "Copiar",
                                  "dueDate": "2026-11-16",
                                  "dueTime": "11:00:00",
                                  "tags": ["Tag1"]
                                }
                                """)
                        .get("id")
                        .asLong();

        setClock(T1);

        // Copy to same group (EC-07)
        MvcResult sameGroupCopy =
                mockMvc.perform(
                                post("/api/v1/tasks/{taskId}/copy", pendingSourceId)
                                        .cookie(user.sessionCookie())
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"targetGroupId\": "
                                                        + user.personalGroupId()
                                                        + "}"))
                        .andExpect(status().isCreated())
                        .andExpect(
                                jsonPath("$.id").value(org.hamcrest.Matchers.not(pendingSourceId)))
                        .andExpect(jsonPath("$.groupId").value(user.personalGroupId()))
                        .andExpect(jsonPath("$.title").value("Pendiente"))
                        .andExpect(jsonPath("$.createdAt").value(T1.toString()))
                        .andExpect(jsonPath("$.tags.length()").value(1))
                        .andReturn();
        long sameCopyId =
                objectMapper
                        .readTree(sameGroupCopy.getResponse().getContentAsString())
                        .get("id")
                        .asLong();
        assertThat(findScheduledExecutionTime(sameCopyId))
                .isEqualTo(Instant.parse("2026-11-16T16:00:00Z"));

        // Copy to different group
        MvcResult diffGroupCopy =
                mockMvc.perform(
                                post("/api/v1/tasks/{taskId}/copy", pendingSourceId)
                                        .cookie(user.sessionCookie())
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"targetGroupId\": " + targetGroupId + "}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.groupId").value(targetGroupId))
                        .andExpect(jsonPath("$.tags[0].groupId").value(targetGroupId))
                        .andReturn();
        long diffCopyId =
                objectMapper
                        .readTree(diffGroupCopy.getResponse().getContentAsString())
                        .get("id")
                        .asLong();
        assertThat(findScheduledExecutionTime(diffCopyId))
                .isEqualTo(Instant.parse("2026-11-16T16:00:00Z"));

        // Complete source task, then copy -> copied task preserves completed=true and NEVER
        // schedules job
        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}/completion", pendingSourceId)
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"completed\": true}"))
                .andExpect(status().isOk());

        MvcResult completedCopy =
                mockMvc.perform(
                                post("/api/v1/tasks/{taskId}/copy", pendingSourceId)
                                        .cookie(user.sessionCookie())
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"targetGroupId\": " + targetGroupId + "}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.completed").value(true))
                        .andExpect(jsonPath("$.completedAt").value(T1.toString()))
                        .andExpect(jsonPath("$.overdue").value(false))
                        .andReturn();
        long completedCopyId =
                objectMapper
                        .readTree(completedCopy.getResponse().getContentAsString())
                        .get("id")
                        .asLong();
        assertThat(findScheduledExecutionTime(completedCopyId)).isNull();
    }

    @Test
    @DisplayName(
            "TSK-REQ-14 strict precedence on /move and /copy: 401 -> 400 -> task(404/403) ->"
                    + " targetGroup(404/403)")
    void moveAndCopyStrictPrecedence() throws Exception {
        AuthenticatedUser owner =
                registerAndGetSession("precowner@example.com", "precowner", "UTC");
        AuthenticatedUser other =
                registerAndGetSession("precother@example.com", "precother", "UTC");

        long ownerTaskId =
                createTask(
                                owner.sessionCookie(),
                                owner.personalGroupId(),
                                "{\"title\": \"Propia\"}")
                        .get("id")
                        .asLong();
        long otherTaskId =
                createTask(other.sessionCookie(), other.personalGroupId(), "{\"title\": \"Ajena\"}")
                        .get("id")
                        .asLong();

        for (String action : List.of("move", "copy")) {
            String url = "/api/v1/tasks/{taskId}/" + action;

            // 401 unauthenticated
            mockMvc.perform(
                            post(url, ownerTaskId)
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"targetGroupId\": " + owner.personalGroupId() + "}"))
                    .andExpect(status().isUnauthorized());

            // 400 null targetGroupId even with non-existent taskId
            mockMvc.perform(
                            post(url, 999999L)
                                    .cookie(owner.sessionCookie())
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.invalidParams[0].name").value("targetGroupId"));

            // 404 missing taskId even with foreign targetGroupId
            mockMvc.perform(
                            post(url, 999999L)
                                    .cookie(owner.sessionCookie())
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"targetGroupId\": " + other.personalGroupId() + "}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Tarea no encontrada"));

            // 403 foreign taskId even with missing targetGroupId
            mockMvc.perform(
                            post(url, otherTaskId)
                                    .cookie(owner.sessionCookie())
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"targetGroupId\": 999999}"))
                    .andExpect(status().isForbidden());

            // 404 missing targetGroupId on owned taskId
            mockMvc.perform(
                            post(url, ownerTaskId)
                                    .cookie(owner.sessionCookie())
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"targetGroupId\": 999999}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Grupo no encontrado"));

            // 403 foreign targetGroupId on owned taskId
            mockMvc.perform(
                            post(url, ownerTaskId)
                                    .cookie(owner.sessionCookie())
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"targetGroupId\": " + other.personalGroupId() + "}"))
                    .andExpect(status().isForbidden());
        }
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
