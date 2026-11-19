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
import com.eyelesscode.task_by_task.shared.pagination.KeysetCursorCodec;
import com.eyelesscode.task_by_task.shared.ratelimit.RateLimitService;
import com.eyelesscode.task_by_task.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

class TaskPaginationAndFilterIntegrationTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-11-15T12:00:00Z");

    @Autowired private WebApplicationContext context;
    @Autowired private SessionRepositoryFilter<? extends Session> sessionRepositoryFilter;
    @Autowired private UserRepository userRepository;
    @Autowired private RateLimitService rateLimitService;
    @Autowired private KeysetCursorCodec keysetCursorCodec;
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

    private AuthenticatedUser registerAndGetSession(String email, String username)
            throws Exception {
        String payload =
                String.format(
                        """
                        {
                          "email": "%s",
                          "username": "%s",
                          "displayName": "Test User",
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

    private long createTask(Cookie cookie, long groupId, String jsonPayload) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/groups/{groupId}/tasks", groupId)
                                        .cookie(cookie)
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(jsonPayload))
                        .andExpect(status().isCreated())
                        .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private void markCompleted(Cookie cookie, long taskId) throws Exception {
        mockMvc.perform(
                        patch("/api/v1/tasks/{taskId}/completion", taskId)
                                .cookie(cookie)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"completed\": true}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName(
            "Keyset pagination traverses all 3 dueDate/dueTime forms, tie-breakers, and ALL"
                    + " false->true transition with limit=1 and limit=2")
    void keysetTraversalAcrossAllFormsAndTieBreakers() throws Exception {
        AuthenticatedUser user = registerAndGetSession("page@example.com", "pageuser");
        long g2 = createGroup(user.sessionCookie(), "Trabajo");

        // Create tasks in scrambled order to verify composite ORDER BY:
        // 1. P-Nov16-09:00 (created T0+1s)
        // 2. P-Nov16-null (created T0+2s)
        // 3. P-Nov17-null-first (created T0+3s, id A)
        // 4. P-Nov17-null-second (created T0+3s, id B > A) -> tests (createdAt, id) tie-breaker
        // 5. P-null-null (created T0+4s)
        // 6. C-Nov15-08:00 (completed, created T0+5s)
        // 7. C-null-null (completed, created T0+6s)

        setClock(T0.plusSeconds(4));
        long pNoDate = createTask(user.sessionCookie(), g2, "{\"title\": \"5-P-NoDate\"}");

        setClock(T0.plusSeconds(3));
        long pNov17First =
                createTask(
                        user.sessionCookie(),
                        user.personalGroupId(),
                        "{\"title\": \"3-P-Nov17-First\", \"dueDate\": \"2026-11-17\"}");
        long pNov17Second =
                createTask(
                        user.sessionCookie(),
                        g2,
                        "{\"title\": \"4-P-Nov17-Second\", \"dueDate\": \"2026-11-17\"}");

        setClock(T0.plusSeconds(2));
        long pNov16NoTime =
                createTask(
                        user.sessionCookie(),
                        user.personalGroupId(),
                        "{\"title\": \"2-P-Nov16-NoTime\", \"dueDate\": \"2026-11-16\"}");

        setClock(T0.plusSeconds(1));
        long pNov16Timed =
                createTask(
                        user.sessionCookie(),
                        user.personalGroupId(),
                        "{\"title\": \"1-P-Nov16-09:00\", \"dueDate\": \"2026-11-16\","
                                + " \"dueTime\": \"09:00:00\"}");

        setClock(T0.plusSeconds(5));
        long cNov15Timed =
                createTask(
                        user.sessionCookie(),
                        user.personalGroupId(),
                        "{\"title\": \"6-C-Nov15-08:00\", \"dueDate\": \"2026-11-15\","
                                + " \"dueTime\": \"08:00:00\"}");
        markCompleted(user.sessionCookie(), cNov15Timed);

        setClock(T0.plusSeconds(6));
        long cNoDate = createTask(user.sessionCookie(), g2, "{\"title\": \"7-C-NoDate\"}");
        markCompleted(user.sessionCookie(), cNoDate);

        List<Long> expectedAllOrder =
                List.of(
                        pNov16Timed,
                        pNov16NoTime,
                        pNov17First,
                        pNov17Second,
                        pNoDate,
                        cNov15Timed,
                        cNoDate);

        // Traverse ALL with limit=1
        assertThat(collectAllIds(user.sessionCookie(), "/api/v1/tasks?status=ALL&limit=1"))
                .containsExactlyElementsOf(expectedAllOrder);

        // Traverse ALL with limit=2
        assertThat(collectAllIds(user.sessionCookie(), "/api/v1/tasks?status=ALL&limit=2"))
                .containsExactlyElementsOf(expectedAllOrder);

        // Default status (PENDING) with limit=2
        assertThat(collectAllIds(user.sessionCookie(), "/api/v1/tasks?limit=2"))
                .containsExactly(pNov16Timed, pNov16NoTime, pNov17First, pNov17Second, pNoDate);

        // COMPLETED with limit=1
        assertThat(collectAllIds(user.sessionCookie(), "/api/v1/tasks?status=COMPLETED&limit=1"))
                .containsExactly(cNov15Timed, cNoDate);
    }

    @Test
    @DisplayName(
            "Combined filters: search on title only with SQL wildcards (EC-08), groupIds"
                    + " empty/duplicated/precedence, and /groups/{id}/tasks")
    void filtersSearchWildcardsAndGroupPrecedence() throws Exception {
        AuthenticatedUser owner = registerAndGetSession("filt@example.com", "filtuser");
        AuthenticatedUser other = registerAndGetSession("other@example.com", "otherfilt");
        long g2 = createGroup(owner.sessionCookie(), "Secundario");

        createTask(
                owner.sessionCookie(),
                owner.personalGroupId(),
                "{\"title\": \"100%_done\\\\ok\", \"description\": \"nada\"}");
        createTask(
                owner.sessionCookie(),
                g2,
                "{\"title\": \"Otra tarea\", \"description\": \"100%_done\\\\ok en desc\"}");
        createTask(
                owner.sessionCookie(),
                g2,
                "{\"title\": \"100X_done\\\\ok\", \"description\": \"wildcard test\"}");

        // Search matches title only and escapes %, _, \
        mockMvc.perform(
                        get("/api/v1/tasks")
                                .cookie(owner.sessionCookie())
                                .param("search", "100%_done\\"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("100%_done\\ok"));

        // ?groupIds= empty returns all owned groups
        mockMvc.perform(get("/api/v1/tasks?groupIds=").cookie(owner.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3));

        // Duplicated groupIds work cleanly
        mockMvc.perform(
                        get("/api/v1/tasks")
                                .cookie(owner.sessionCookie())
                                .param("groupIds", String.valueOf(g2), String.valueOf(g2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));

        // 404 before 403 precedence on groupIds (EC-08)
        mockMvc.perform(
                        get("/api/v1/tasks")
                                .cookie(owner.sessionCookie())
                                .param(
                                        "groupIds",
                                        "999999",
                                        String.valueOf(other.personalGroupId())))
                .andExpect(status().isNotFound());

        // 403 when all exist but one is foreign
        mockMvc.perform(
                        get("/api/v1/tasks")
                                .cookie(owner.sessionCookie())
                                .param(
                                        "groupIds",
                                        String.valueOf(owner.personalGroupId()),
                                        String.valueOf(other.personalGroupId())))
                .andExpect(status().isForbidden());

        // /groups/{groupId}/tasks scoped + 404/403
        mockMvc.perform(
                        get("/api/v1/groups/{groupId}/tasks", owner.personalGroupId())
                                .cookie(owner.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));

        mockMvc.perform(
                        get("/api/v1/groups/{groupId}/tasks", 999999L)
                                .cookie(owner.sessionCookie()))
                .andExpect(status().isNotFound());

        mockMvc.perform(
                        get("/api/v1/groups/{groupId}/tasks", other.personalGroupId())
                                .cookie(owner.sessionCookie()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Cursor validation, deleted cursor target, full tag list (>5 tags), and 400/401")
    void cursorValidationDeletedTargetAndFullTags() throws Exception {
        AuthenticatedUser user = registerAndGetSession("cur@example.com", "curuser");

        long t1 =
                createTask(
                        user.sessionCookie(),
                        user.personalGroupId(),
                        """
                        {
                          "title": "Con 6 tags",
                          "tags": ["Zeta", "Alfa", "Beta", "Delta", "Epsilon", "Gamma"]
                        }
                        """);
        setClock(T0.plusSeconds(1));
        long t2 =
                createTask(
                        user.sessionCookie(), user.personalGroupId(), "{\"title\": \"Segunda\"}");

        // Page 1 (limit=1) returns full 6-tag list sorted alphabetically
        MvcResult page1 =
                mockMvc.perform(get("/api/v1/tasks?limit=1").cookie(user.sessionCookie()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.content[0].id").value(t1))
                        .andExpect(jsonPath("$.content[0].tags.length()").value(6))
                        .andExpect(jsonPath("$.content[0].tags[0].name").value("Alfa"))
                        .andExpect(jsonPath("$.content[0].tags[5].name").value("Zeta"))
                        .andExpect(jsonPath("$.hasNext").value(true))
                        .andReturn();
        String pendingCursor =
                objectMapper
                        .readTree(page1.getResponse().getContentAsString())
                        .get("nextCursor")
                        .asString();

        // Incompatible cursor (PENDING cursor used on COMPLETED) -> 400
        mockMvc.perform(
                        get("/api/v1/tasks")
                                .cookie(user.sessionCookie())
                                .param("status", "COMPLETED")
                                .param("cursor", pendingCursor))
                .andExpect(status().isBadRequest());

        // Incompatible cursor (COMPLETED cursor used on PENDING) -> 400
        String completedCursor = keysetCursorCodec.encodeTaskCursor(true, null, null, T0, t1);
        mockMvc.perform(
                        get("/api/v1/tasks")
                                .cookie(user.sessionCookie())
                                .param("status", "PENDING")
                                .param("cursor", completedCursor))
                .andExpect(status().isBadRequest());

        // Wrong cursor type / corrupted -> 400
        String groupCursor = keysetCursorCodec.encodeGroupCursor(true, T0, 1L);
        mockMvc.perform(
                        get("/api/v1/tasks")
                                .cookie(user.sessionCookie())
                                .param("cursor", groupCursor))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        get("/api/v1/tasks")
                                .cookie(user.sessionCookie())
                                .param("cursor", "corrupted-token"))
                .andExpect(status().isBadRequest());

        // Delete t1 (the cursor anchor) -> fetching with pendingCursor still cleanly returns t2,
        // then empty
        mockMvc.perform(
                        delete("/api/v1/tasks/{taskId}", t1)
                                .cookie(user.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(
                        get("/api/v1/tasks")
                                .cookie(user.sessionCookie())
                                .param("cursor", pendingCursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(t2));

        // Delete t2 -> cursor now yields empty 200 OK page
        mockMvc.perform(
                        delete("/api/v1/tasks/{taskId}", t2)
                                .cookie(user.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(
                        get("/api/v1/tasks")
                                .cookie(user.sessionCookie())
                                .param("cursor", pendingCursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.hasNext").value(false));

        // Invalid limit, status, groupIds type, and 401
        mockMvc.perform(get("/api/v1/tasks?limit=0").cookie(user.sessionCookie()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/tasks?limit=51").cookie(user.sessionCookie()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/tasks?status=INVALID").cookie(user.sessionCookie()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/tasks?groupIds=abc").cookie(user.sessionCookie()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/tasks")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/groups/{groupId}/tasks", user.personalGroupId()))
                .andExpect(status().isUnauthorized());
    }

    private List<Long> collectAllIds(Cookie cookie, String baseUrl) throws Exception {
        List<Long> ids = new ArrayList<>();
        String cursor = null;
        do {
            String url = cursor == null ? baseUrl : baseUrl + "&cursor=" + cursor;
            MvcResult res =
                    mockMvc.perform(get(url).cookie(cookie)).andExpect(status().isOk()).andReturn();
            JsonNode root = objectMapper.readTree(res.getResponse().getContentAsString());
            for (JsonNode item : root.get("content")) {
                ids.add(item.get("id").asLong());
            }
            boolean hasNext = root.get("hasNext").asBoolean();
            cursor = hasNext ? root.get("nextCursor").asString() : null;
        } while (cursor != null);
        return ids;
    }
}
