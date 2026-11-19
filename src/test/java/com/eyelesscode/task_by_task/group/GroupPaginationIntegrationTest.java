package com.eyelesscode.task_by_task.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eyelesscode.task_by_task.shared.AbstractIntegrationTest;
import com.eyelesscode.task_by_task.shared.pagination.KeysetCursorCodec;
import com.eyelesscode.task_by_task.shared.ratelimit.RateLimitService;
import com.eyelesscode.task_by_task.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GroupPaginationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private SessionRepositoryFilter<? extends Session> sessionRepositoryFilter;
    @Autowired private UserRepository userRepository;
    @Autowired private GroupRepository groupRepository;
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

    private Long createGroupAndGetId(Cookie sessionCookie, String name) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/groups")
                                        .cookie(sessionCookie)
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\": \"" + name + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    @DisplayName(
            "GET /api/v1/groups coloca siempre primero al grupo Personal incluso tras renombrarlo y"
                    + " recorre todas las páginas Keyset sin duplicados ni omisiones (GRP-REQ-06,"
                    + " GRP-REQ-07, GRP-REQ-08, EC-02)")
    void shouldReturnPersonalGroupFirstEvenWhenRenamedAndPaginateWithoutDuplicates()
            throws Exception {
        AuthenticatedUser user = registerAndGetSession("page@example.com", "pageuser");
        Group personalGroup =
                groupRepository.findByCreatorIdAndIsPersonalTrue(user.userId()).orElseThrow();

        // Rename Personal group and create 4 individual groups
        jdbcTemplate.update(
                "UPDATE groups SET name = 'Bandeja Principal' WHERE id = ?", personalGroup.getId());

        Long g1 = createGroupAndGetId(user.sessionCookie(), "Grupo A");
        Long g2 = createGroupAndGetId(user.sessionCookie(), "Grupo B");
        Long g3 = createGroupAndGetId(user.sessionCookie(), "Grupo C");
        Long g4 = createGroupAndGetId(user.sessionCookie(), "Grupo D");

        // Ensure strict distinct created_at ordering for deterministic traversal assertion
        Instant base = Instant.now().minus(10, ChronoUnit.MINUTES);
        jdbcTemplate.update(
                "UPDATE groups SET created_at = ? WHERE id = ?",
                Timestamp.from(base.plusSeconds(100)),
                personalGroup.getId());
        jdbcTemplate.update(
                "UPDATE groups SET created_at = ? WHERE id = ?",
                Timestamp.from(base.plusSeconds(10)),
                g1);
        jdbcTemplate.update(
                "UPDATE groups SET created_at = ? WHERE id = ?",
                Timestamp.from(base.plusSeconds(20)),
                g2);
        jdbcTemplate.update(
                "UPDATE groups SET created_at = ? WHERE id = ?",
                Timestamp.from(base.plusSeconds(30)),
                g3);
        jdbcTemplate.update(
                "UPDATE groups SET created_at = ? WHERE id = ?",
                Timestamp.from(base.plusSeconds(40)),
                g4);

        // Page 1 (limit=2) -> [Personal (even with newer created_at), g1]
        MvcResult page1Result =
                mockMvc.perform(get("/api/v1/groups?limit=2").cookie(user.sessionCookie()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.size").value(2))
                        .andExpect(jsonPath("$.hasNext").value(true))
                        .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                        .andExpect(jsonPath("$.content[0].id").value(personalGroup.getId()))
                        .andExpect(jsonPath("$.content[0].name").value("Bandeja Principal"))
                        .andExpect(jsonPath("$.content[0].isPersonal").value(true))
                        .andExpect(jsonPath("$.content[1].id").value(g1))
                        .andReturn();

        String cursor1 =
                objectMapper
                        .readTree(page1Result.getResponse().getContentAsString())
                        .get("nextCursor")
                        .asString();

        // Page 2 (limit=2) -> [g2, g3]
        MvcResult page2Result =
                mockMvc.perform(
                                get("/api/v1/groups")
                                        .param("cursor", cursor1)
                                        .param("limit", "2")
                                        .cookie(user.sessionCookie()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.size").value(2))
                        .andExpect(jsonPath("$.hasNext").value(true))
                        .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                        .andExpect(jsonPath("$.content[0].id").value(g2))
                        .andExpect(jsonPath("$.content[1].id").value(g3))
                        .andReturn();

        String cursor2 =
                objectMapper
                        .readTree(page2Result.getResponse().getContentAsString())
                        .get("nextCursor")
                        .asString();

        // Page 3 (limit=2) -> [g4]
        mockMvc.perform(
                        get("/api/v1/groups")
                                .param("cursor", cursor2)
                                .param("limit", "2")
                                .cookie(user.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").isEmpty())
                .andExpect(jsonPath("$.content[0].id").value(g4));
    }

    @Test
    @DisplayName(
            "GET /api/v1/groups desempata determinísticamente por id ASC cuando múltiples grupos"
                    + " comparten exactamente el mismo created_at (GRP-REQ-06, EC-07)")
    void shouldTieBreakByIdAscendingWhenCreatedAtIsIdentical() throws Exception {
        AuthenticatedUser user = registerAndGetSession("tie@example.com", "tieuser");
        Group personalGroup =
                groupRepository.findByCreatorIdAndIsPersonalTrue(user.userId()).orElseThrow();

        Long g1 = createGroupAndGetId(user.sessionCookie(), "Mismo Tiempo 1");
        Long g2 = createGroupAndGetId(user.sessionCookie(), "Mismo Tiempo 2");
        Long g3 = createGroupAndGetId(user.sessionCookie(), "Mismo Tiempo 3");

        Instant identicalInstant = Instant.parse("2026-01-15T10:00:00.123456Z");
        jdbcTemplate.update(
                "UPDATE groups SET created_at = ? WHERE id IN (?, ?, ?)",
                Timestamp.from(identicalInstant),
                g1,
                g2,
                g3);

        List<Long> collectedIds = new ArrayList<>();
        String cursor = null;
        boolean hasNext = true;

        while (hasNext) {
            var request = get("/api/v1/groups").param("limit", "1").cookie(user.sessionCookie());
            if (cursor != null) {
                request = request.param("cursor", cursor);
            }

            MvcResult res = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
            JsonNode root = objectMapper.readTree(res.getResponse().getContentAsString());

            assertThat(root.get("content").size()).isEqualTo(1);
            collectedIds.add(root.get("content").get(0).get("id").asLong());
            hasNext = root.get("hasNext").asBoolean();
            cursor = root.get("nextCursor").isNull() ? null : root.get("nextCursor").asString();
        }

        assertThat(collectedIds).containsExactly(personalGroup.getId(), g1, g2, g3);
    }

    @Test
    @DisplayName(
            "GET /api/v1/groups aísla usuarios y devuelve página vacía ante cursores más allá del"
                    + " final, de grupos eliminados o de otro usuario (EC-09)")
    void shouldIsolateUsersAndReturnEmptyPageForDeletedOrOtherUserCursor() throws Exception {
        AuthenticatedUser userA = registerAndGetSession("isoa@example.com", "isoa");
        AuthenticatedUser userB = registerAndGetSession("isob@example.com", "isob");

        Long userAGroup = createGroupAndGetId(userA.sessionCookie(), "Grupo A1");
        Long userBGroup = createGroupAndGetId(userB.sessionCookie(), "Grupo B1");

        // User A default listing only sees User A's groups (Personal + Grupo A1)
        mockMvc.perform(get("/api/v1/groups").cookie(userA.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.content[0].creatorId").value(userA.userId()))
                .andExpect(jsonPath("$.content[1].id").value(userAGroup));

        // Cursor pointing past the last item
        Group lastUserAGroup = groupRepository.findById(userAGroup).orElseThrow();
        String pastEndCursor =
                keysetCursorCodec.encodeGroupCursor(
                        lastUserAGroup.isPersonal(),
                        lastUserAGroup.getCreatedAt(),
                        lastUserAGroup.getId());

        mockMvc.perform(
                        get("/api/v1/groups")
                                .param("cursor", pastEndCursor)
                                .cookie(userA.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.nextCursor").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.size").value(0));

        // Cursor pointing to a deleted group (last position)
        groupRepository.delete(lastUserAGroup);
        mockMvc.perform(
                        get("/api/v1/groups")
                                .param("cursor", pastEndCursor)
                                .cookie(userA.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.nextCursor").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.size").value(0));

        // Cursor from User B's tuple (whose created_at is after User A's remaining Personal group)
        Group otherUserGroup = groupRepository.findById(userBGroup).orElseThrow();
        String otherUserCursor =
                keysetCursorCodec.encodeGroupCursor(
                        otherUserGroup.isPersonal(),
                        otherUserGroup.getCreatedAt().plusSeconds(60),
                        otherUserGroup.getId());

        mockMvc.perform(
                        get("/api/v1/groups")
                                .param("cursor", otherUserCursor)
                                .cookie(userA.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.nextCursor").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.size").value(0));
    }

    @Test
    @DisplayName(
            "GET /api/v1/groups rechaza limit fuera de 1..50, limit no entero, cursor"
                    + " vacío/corrupto/TASK con 400 RFC 7807 y sin sesión con 401 (GRP-REQ-09,"
                    + " GRP-REQ-10, GRP-REQ-23, GRP-REQ-25, EC-08, EC-10)")
    void shouldRejectInvalidLimitInvalidCursorsAndUnauthenticatedRequests() throws Exception {
        AuthenticatedUser user = registerAndGetSession("err@example.com", "erruser");

        // limit < 1
        mockMvc.perform(get("/api/v1/groups?limit=0").cookie(user.sessionCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail")
                                .value("El límite de paginación debe estar entre 1 y 50"));

        // limit > 50
        mockMvc.perform(get("/api/v1/groups?limit=51").cookie(user.sessionCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail")
                                .value("El límite de paginación debe estar entre 1 y 50"));

        // non-integer limit
        mockMvc.perform(get("/api/v1/groups?limit=xyz").cookie(user.sessionCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "El cuerpo o parámetro de la solicitud no tiene un formato"
                                                + " válido"));

        // blank cursor
        mockMvc.perform(get("/api/v1/groups").param("cursor", "   ").cookie(user.sessionCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail")
                                .value("El cursor de paginación proporcionado no es válido"));

        // corrupted cursor
        mockMvc.perform(
                        get("/api/v1/groups")
                                .param("cursor", "corrupted-token")
                                .cookie(user.sessionCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail")
                                .value("El cursor de paginación proporcionado no es válido"));

        // wrong cursor type (TASK cursor)
        String taskCursor =
                keysetCursorCodec.encodeTaskCursor(false, null, null, Instant.now(), 10L);
        mockMvc.perform(
                        get("/api/v1/groups")
                                .param("cursor", taskCursor)
                                .cookie(user.sessionCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail")
                                .value("El cursor de paginación proporcionado no es válido"));

        // unauthenticated
        mockMvc.perform(get("/api/v1/groups"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("No autenticado"))
                .andExpect(jsonPath("$.status").value(401));
    }
}
