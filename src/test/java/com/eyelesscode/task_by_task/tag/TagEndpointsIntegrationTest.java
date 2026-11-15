package com.eyelesscode.task_by_task.tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eyelesscode.task_by_task.group.Group;
import com.eyelesscode.task_by_task.group.GroupRepository;
import com.eyelesscode.task_by_task.shared.AbstractIntegrationTest;
import com.eyelesscode.task_by_task.shared.pagination.KeysetCursorCodec;
import com.eyelesscode.task_by_task.shared.ratelimit.RateLimitService;
import com.eyelesscode.task_by_task.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class TagEndpointsIntegrationTest extends AbstractIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private SessionRepositoryFilter<? extends Session> sessionRepositoryFilter;
    @Autowired private UserRepository userRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private TagService tagService;
    @Autowired private KeysetCursorCodec keysetCursorCodec;
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

        return new AuthenticatedUser(userId, sessionCookie);
    }

    private Long createGroup(Cookie cookie, String name) throws Exception {
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

    @Test
    @DisplayName(
            "POST /api/v1/groups/{groupId}/tags crea con 201, reutiliza con 200 ignorando"
                    + " mayúsculas/espacios y rechaza 400/401/403/404 (TAG-REQ-05, TAG-REQ-06,"
                    + " TSK-REQ-23..27)")
    void shouldCreateAndTransparentlyReuseTagsWithValidationAndSecurity() throws Exception {
        AuthenticatedUser userA = registerAndGetSession("taga@example.com", "taga");
        AuthenticatedUser userB = registerAndGetSession("tagb@example.com", "tagb");

        Long groupA = createGroup(userA.sessionCookie(), "Trabajo");
        Long groupB = createGroup(userB.sessionCookie(), "Otro");

        // 201 Created on new tag (trimmed)
        MvcResult createResult =
                mockMvc.perform(
                                post("/api/v1/groups/" + groupA + "/tags")
                                        .cookie(userA.sessionCookie())
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\": \"  Urgente  \"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.id").isNumber())
                        .andExpect(jsonPath("$.groupId").value(groupA))
                        .andExpect(jsonPath("$.name").value("Urgente"))
                        .andExpect(jsonPath("$.createdAt").isNotEmpty())
                        .andReturn();

        Long tagId =
                objectMapper
                        .readTree(createResult.getResponse().getContentAsString())
                        .get("id")
                        .asLong();

        // 200 OK on transparent reuse ignoring case/whitespace
        mockMvc.perform(
                        post("/api/v1/groups/" + groupA + "/tags")
                                .cookie(userA.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"  uRgEnTe  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tagId))
                .andExpect(jsonPath("$.name").value("Urgente"));

        // 400 validation errors: blank, >30 chars, ',' and ';'
        mockMvc.perform(
                        post("/api/v1/groups/" + groupA + "/tags")
                                .cookie(userA.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Error de validación"))
                .andExpect(jsonPath("$.invalidParams[0].name").value("name"));

        mockMvc.perform(
                        post("/api/v1/groups/" + groupA + "/tags")
                                .cookie(userA.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"" + "A".repeat(31) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Error de validación"))
                .andExpect(jsonPath("$.invalidParams[0].name").value("name"));

        mockMvc.perform(
                        post("/api/v1/groups/" + groupA + "/tags")
                                .cookie(userA.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"uno,dos\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Error de validación"))
                .andExpect(jsonPath("$.invalidParams[0].name").value("name"));

        mockMvc.perform(
                        post("/api/v1/groups/" + groupA + "/tags")
                                .cookie(userA.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"uno;dos\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Error de validación"))
                .andExpect(jsonPath("$.invalidParams[0].name").value("name"));

        // 401 unauthenticated, 403 missing CSRF, 403 foreign group, 404 non-existent group
        mockMvc.perform(
                        post("/api/v1/groups/" + groupA + "/tags")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"Test\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(
                        post("/api/v1/groups/" + groupA + "/tags")
                                .cookie(userA.sessionCookie())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"Test\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(
                        post("/api/v1/groups/" + groupB + "/tags")
                                .cookie(userA.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"Test\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(
                        post("/api/v1/groups/999999/tags")
                                .cookie(userA.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"Test\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Grupo no encontrado"));
    }

    @Test
    @DisplayName(
            "GET /api/v1/groups/{groupId}/tags pagina con TagCursor, filtra por prefijo escapando"
                    + " %, _, \\ y valida limit/cursor/permisos (TAG-REQ-01..04, EC-08, EC-09)")
    void shouldListAndFilterGroupTagsWithKeysetAndSqlEscape() throws Exception {
        AuthenticatedUser user = registerAndGetSession("listtag@example.com", "listtag");
        AuthenticatedUser other = registerAndGetSession("other@example.com", "otheruser");

        Long groupId = createGroup(user.sessionCookie(), "Etiquetas");
        Long otherGroupId = createGroup(other.sessionCookie(), "Ajeno");

        for (String tag : List.of("Alpha", "Beta", "Gamma", "100%_ok", "100x_other", "path\\sub")) {
            mockMvc.perform(
                            post("/api/v1/groups/" + groupId + "/tags")
                                    .cookie(user.sessionCookie())
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    java.util.Map.of("name", tag))))
                    .andExpect(status().isCreated());
        }

        // Paginate limit=2 (sorted ASCII/Unicode: 100%_ok, 100x_other, Alpha, Beta, Gamma,
        // path\sub)
        MvcResult page1 =
                mockMvc.perform(
                                get("/api/v1/groups/" + groupId + "/tags?limit=2")
                                        .cookie(user.sessionCookie()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.size").value(2))
                        .andExpect(jsonPath("$.hasNext").value(true))
                        .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                        .andExpect(jsonPath("$.content[0].name").value("100%_ok"))
                        .andExpect(jsonPath("$.content[1].name").value("100x_other"))
                        .andReturn();

        String cursor1 =
                objectMapper
                        .readTree(page1.getResponse().getContentAsString())
                        .get("nextCursor")
                        .asString();

        mockMvc.perform(
                        get("/api/v1/groups/" + groupId + "/tags")
                                .param("cursor", cursor1)
                                .param("limit", "4")
                                .cookie(user.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(4))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").isEmpty())
                .andExpect(jsonPath("$.content[0].name").value("Alpha"))
                .andExpect(jsonPath("$.content[3].name").value("path\\sub"));

        // Prefix query escaping % and _ (must match ONLY "100%_ok", NOT "100x_other")
        mockMvc.perform(
                        get("/api/v1/groups/" + groupId + "/tags")
                                .param("query", "100%_")
                                .cookie(user.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.content[0].name").value("100%_ok"));

        // Prefix query with backslash
        mockMvc.perform(
                        get("/api/v1/groups/" + groupId + "/tags")
                                .param("query", "PATH\\")
                                .cookie(user.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.content[0].name").value("path\\sub"));

        // Validation & security errors
        mockMvc.perform(
                        get("/api/v1/groups/" + groupId + "/tags?limit=51")
                                .cookie(user.sessionCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.detail")
                                .value("El límite de paginación debe estar entre 1 y 50"));

        mockMvc.perform(
                        get("/api/v1/groups/" + groupId + "/tags")
                                .param("cursor", "   ")
                                .cookie(user.sessionCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.detail")
                                .value("El cursor de paginación proporcionado no es válido"));

        String wrongTypeCursor = keysetCursorCodec.encodeGroupCursor(false, Instant.now(), 1L);
        mockMvc.perform(
                        get("/api/v1/groups/" + groupId + "/tags")
                                .param("cursor", wrongTypeCursor)
                                .cookie(user.sessionCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.detail")
                                .value("El cursor de paginación proporcionado no es válido"));

        mockMvc.perform(
                        get("/api/v1/groups/" + otherGroupId + "/tags")
                                .cookie(user.sessionCookie()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/groups/999999/tags").cookie(user.sessionCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Grupo no encontrado"));
    }

    @Test
    @DisplayName(
            "DELETE /api/v1/groups/{groupId}/tags/{tagId} respeta precedencia 404/403 y elimina en"
                    + " cascada task_tags preservando tasks (TAG-REQ-08, TAG-REQ-09, EC-06)")
    void shouldEnforceDeletePrecedenceAndCascadeTaskTagsPreservingTasks() throws Exception {
        AuthenticatedUser userA = registerAndGetSession("dela@example.com", "dela");
        AuthenticatedUser userB = registerAndGetSession("delb@example.com", "delb");

        Long groupA1 = createGroup(userA.sessionCookie(), "Grupo A1");
        Long groupA2 = createGroup(userA.sessionCookie(), "Grupo A2");
        Long groupB = createGroup(userB.sessionCookie(), "Grupo B");

        Long tagA1 =
                objectMapper
                        .readTree(
                                mockMvc.perform(
                                                post("/api/v1/groups/" + groupA1 + "/tags")
                                                        .cookie(userA.sessionCookie())
                                                        .with(csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content("{\"name\": \"Prioridad\"}"))
                                        .andExpect(status().isCreated())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get("id")
                        .asLong();

        Long tagB =
                objectMapper
                        .readTree(
                                mockMvc.perform(
                                                post("/api/v1/groups/" + groupB + "/tags")
                                                        .cookie(userB.sessionCookie())
                                                        .with(csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content("{\"name\": \"Ajena\"}"))
                                        .andExpect(status().isCreated())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get("id")
                        .asLong();

        // Precedence checks
        mockMvc.perform(
                        delete("/api/v1/groups/999999/tags/" + tagA1)
                                .cookie(userA.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Grupo no encontrado"));

        mockMvc.perform(
                        delete("/api/v1/groups/" + groupB + "/tags/" + tagB)
                                .cookie(userA.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(
                        delete("/api/v1/groups/" + groupA1 + "/tags/999999")
                                .cookie(userA.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Etiqueta no encontrada"));

        // Foreign tagId inside own groupId -> 403 Forbidden
        mockMvc.perform(
                        delete("/api/v1/groups/" + groupA1 + "/tags/" + tagB)
                                .cookie(userA.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isForbidden());

        // Own tagId belonging to a different own group -> 404 Not Found
        mockMvc.perform(
                        delete("/api/v1/groups/" + groupA2 + "/tags/" + tagA1)
                                .cookie(userA.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Etiqueta no encontrada"));

        // Cascade task_tags while preserving tasks
        Long taskId =
                jdbcTemplate.queryForObject(
                        "INSERT INTO tasks (group_id, title, completed) VALUES (?, 'Tarea con"
                                + " tag', false) RETURNING id",
                        Long.class,
                        groupA1);
        jdbcTemplate.update("INSERT INTO task_tags (task_id, tag_id) VALUES (?, ?)", taskId, tagA1);

        mockMvc.perform(
                        delete("/api/v1/groups/" + groupA1 + "/tags/" + tagA1)
                                .cookie(userA.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(tagRepository.findById(tagA1)).isEmpty();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM task_tags WHERE tag_id = ?",
                                Integer.class,
                                tagA1))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM tasks WHERE id = ?", Integer.class, taskId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "Creación concurrente de la misma etiqueta en el mismo grupo resuelve sin errores"
                    + " 500/409 y crea exactamente una fila (EC-10)")
    void shouldHandleConcurrentTagCreationWithoutErrorsOrDuplicates() throws Exception {
        AuthenticatedUser user = registerAndGetSession("race@example.com", "raceuser");
        Long groupId = createGroup(user.sessionCookie(), "Concurrencia");
        Group group = groupRepository.findById(groupId).orElseThrow();

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<Long>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            String variant = (i % 2 == 0) ? "ConcurrenciaTag" : "concurrenciatag";
            Callable<Long> task =
                    () -> {
                        ready.countDown();
                        start.await();
                        return tagService
                                .resolveOrCreateTag(group, variant, Instant.now())
                                .tag()
                                .getId();
                    };
            futures.add(executor.submit(task));
        }

        ready.await();
        start.countDown();

        List<Long> ids = new ArrayList<>();
        for (Future<Long> future : futures) {
            ids.add(future.get());
        }
        executor.shutdown();

        assertThat(ids).hasSize(threads);
        assertThat(ids).containsOnly(ids.getFirst());
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM tags WHERE group_id = ? AND"
                                        + " TRIM(LOWER(name)) = 'concurrenciatag'",
                                Integer.class,
                                groupId))
                .isEqualTo(1);
    }
}
