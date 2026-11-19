package com.eyelesscode.task_by_task.group;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.eyelesscode.task_by_task.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
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

class GroupRenameAndDeleteIntegrationTest extends AbstractIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private SessionRepositoryFilter<? extends Session> sessionRepositoryFilter;
    @Autowired private UserRepository userRepository;
    @Autowired private GroupRepository groupRepository;
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
            "PATCH /api/v1/groups/{groupId} renombra grupo individual y grupo Personal (preservando"
                + " isPersonal=true), soporta idempotencia, ignora isPersonal en JSON y permite"
                + " crear/eliminar un segundo grupo llamado 'Personal' (GRP-REQ-14, EC-02, EC-04,"
                + " EC-11)")
    void shouldRenameIndividualAndPersonalGroupsIdempotentlyAndAllowSecondPersonalNamedGroup()
            throws Exception {
        AuthenticatedUser user = registerAndGetSession("rename@example.com", "renameuser");

        // 1. Rename individual group with trim & ignore "isPersonal": true
        Long indGroupId = createGroupAndGetId(user.sessionCookie(), "Inicial");
        Group indBefore = groupRepository.findById(indGroupId).orElseThrow();
        Instant past = indBefore.getUpdatedAt().minusSeconds(120);
        jdbcTemplate.update(
                "UPDATE groups SET updated_at = ? WHERE id = ?", Timestamp.from(past), indGroupId);

        mockMvc.perform(
                        patch("/api/v1/groups/" + indGroupId)
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"  Renombrado  \", \"isPersonal\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(indGroupId))
                .andExpect(jsonPath("$.name").value("Renombrado"))
                .andExpect(jsonPath("$.isPersonal").value(false));

        Group indAfter = groupRepository.findById(indGroupId).orElseThrow();
        assertThat(indAfter.getUpdatedAt()).isAfter(past);

        // Idempotent rename with exact same name
        mockMvc.perform(
                        patch("/api/v1/groups/" + indGroupId)
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"Renombrado\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renombrado"))
                .andExpect(jsonPath("$.isPersonal").value(false));

        // 2. Rename default Personal group & ignore "isPersonal": false
        Group personalGroup =
                groupRepository.findByCreatorIdAndIsPersonalTrue(user.userId()).orElseThrow();

        mockMvc.perform(
                        patch("/api/v1/groups/" + personalGroup.getId())
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\": \"Bandeja Principal\", \"isPersonal\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(personalGroup.getId()))
                .andExpect(jsonPath("$.name").value("Bandeja Principal"))
                .andExpect(jsonPath("$.isPersonal").value(true));

        // Default Personal group still appears first and cannot be deleted
        mockMvc.perform(get("/api/v1/groups").cookie(user.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(personalGroup.getId()))
                .andExpect(jsonPath("$.content[0].name").value("Bandeja Principal"))
                .andExpect(jsonPath("$.content[0].isPersonal").value(true));

        mockMvc.perform(
                        delete("/api/v1/groups/" + personalGroup.getId())
                                .cookie(user.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflicto de estado"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "El grupo Personal no puede ser eliminado ni convertido en"
                                                + " compartido"));

        // 3. Create another individual group named "Personal" and delete it cleanly (EC-02)
        Long secondPersonalNamedId = createGroupAndGetId(user.sessionCookie(), "Personal");
        mockMvc.perform(
                        delete("/api/v1/groups/" + secondPersonalNamedId)
                                .cookie(user.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(groupRepository.findById(secondPersonalNamedId)).isEmpty();
    }

    @Test
    @DisplayName(
            "PATCH /api/v1/groups/{groupId} rechaza errores 400 (validación/JSON/ID), 401 sin"
                + " sesión, 403 sin CSRF o grupo ajeno y 404 inexistente (GRP-REQ-15, GRP-REQ-16,"
                + " GRP-REQ-17, GRP-REQ-23, GRP-REQ-24, GRP-REQ-25)")
    void shouldRejectInvalidPatchRequests() throws Exception {
        AuthenticatedUser userA = registerAndGetSession("patcha@example.com", "patcha");
        AuthenticatedUser userB = registerAndGetSession("patchb@example.com", "patchb");

        Long userAGroupId = createGroupAndGetId(userA.sessionCookie(), "Grupo A");
        Long userBGroupId = createGroupAndGetId(userB.sessionCookie(), "Grupo B");

        // 400 validation: blank, <3 after trim, >30
        mockMvc.perform(
                        patch("/api/v1/groups/" + userAGroupId)
                                .cookie(userA.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Error de validación"))
                .andExpect(jsonPath("$.invalidParams[0].name").value("name"));

        mockMvc.perform(
                        patch("/api/v1/groups/" + userAGroupId)
                                .cookie(userA.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"  ab  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Error de validación"))
                .andExpect(jsonPath("$.invalidParams[0].name").value("name"));

        mockMvc.perform(
                        patch("/api/v1/groups/" + userAGroupId)
                                .cookie(userA.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"" + "X".repeat(31) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Error de validación"))
                .andExpect(jsonPath("$.invalidParams[0].name").value("name"));

        // 400 malformed JSON & non-numeric ID
        mockMvc.perform(
                        patch("/api/v1/groups/" + userAGroupId)
                                .cookie(userA.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{bad-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"));

        mockMvc.perform(
                        patch("/api/v1/groups/not-a-number")
                                .cookie(userA.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"Valido\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"));

        // 401 unauthenticated
        mockMvc.perform(
                        patch("/api/v1/groups/" + userAGroupId)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"Valido\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("No autenticado"));

        // 403 missing CSRF
        mockMvc.perform(
                        patch("/api/v1/groups/" + userAGroupId)
                                .cookie(userA.sessionCookie())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"Valido\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Acceso denegado"));

        // 403 another user's group
        mockMvc.perform(
                        patch("/api/v1/groups/" + userBGroupId)
                                .cookie(userA.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"Hackeado\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Acceso denegado"))
                .andExpect(
                        jsonPath("$.detail").value("No tiene permisos para realizar esta acción"));

        // 404 non-existent group
        mockMvc.perform(
                        patch("/api/v1/groups/999999")
                                .cookie(userA.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"Valido\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso no encontrado"))
                .andExpect(jsonPath("$.detail").value("Grupo no encontrado"));
    }

    @Test
    @DisplayName(
            "DELETE /api/v1/groups/{groupId} retorna 409 en Personal propio, 403 en Personal o"
                    + " individual ajeno (precedencia 403 antes que 409), 404 si no existe, 403 sin"
                    + " CSRF y 401 sin sesión (GRP-REQ-18, GRP-REQ-20, GRP-REQ-21, EC-05)")
    void shouldEnforceAuthorizationBeforePersonalProtectionOnDelete() throws Exception {
        AuthenticatedUser userA = registerAndGetSession("dela@example.com", "dela");
        AuthenticatedUser userB = registerAndGetSession("delb@example.com", "delb");

        Group userAPersonal =
                groupRepository.findByCreatorIdAndIsPersonalTrue(userA.userId()).orElseThrow();
        Group userBPersonal =
                groupRepository.findByCreatorIdAndIsPersonalTrue(userB.userId()).orElseThrow();
        Long userBIndividual = createGroupAndGetId(userB.sessionCookie(), "Individual B");

        // 409 on own Personal group
        mockMvc.perform(
                        delete("/api/v1/groups/" + userAPersonal.getId())
                                .cookie(userA.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflicto de estado"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "El grupo Personal no puede ser eliminado ni convertido en"
                                                + " compartido"));

        // 403 on another user's Personal group (403 precedes 409!)
        mockMvc.perform(
                        delete("/api/v1/groups/" + userBPersonal.getId())
                                .cookie(userA.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Acceso denegado"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(
                        jsonPath("$.detail").value("No tiene permisos para realizar esta acción"));

        // 403 on another user's individual group
        mockMvc.perform(
                        delete("/api/v1/groups/" + userBIndividual)
                                .cookie(userA.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Acceso denegado"))
                .andExpect(jsonPath("$.status").value(403));

        // 404 on non-existent group
        mockMvc.perform(delete("/api/v1/groups/999999").cookie(userA.sessionCookie()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso no encontrado"))
                .andExpect(jsonPath("$.detail").value("Grupo no encontrado"));

        // 403 missing CSRF
        mockMvc.perform(delete("/api/v1/groups/" + userBIndividual).cookie(userB.sessionCookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Acceso denegado"));

        // 401 unauthenticated
        mockMvc.perform(delete("/api/v1/groups/" + userBIndividual).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("No autenticado"));
    }

    @Test
    @DisplayName(
            "DELETE /api/v1/groups/{groupId} elimina en cascada tasks, tags, task_tags,"
                + " group_memberships y limpia selectivamente scheduled_tasks (task-due-{taskId})"
                + " preservando otros grupos (GRP-REQ-19, EC-06)")
    void shouldCascadeDeleteGroupAndSelectivelyCleanScheduledTasks() throws Exception {
        AuthenticatedUser user = registerAndGetSession("cascade@example.com", "cascadeuser");

        Long targetGroupId = createGroupAndGetId(user.sessionCookie(), "Grupo a Eliminar");
        Long preservedGroupId = createGroupAndGetId(user.sessionCookie(), "Grupo Intacto");

        // Insert tasks, tags, task_tags, and scheduled_tasks for both groups
        Long targetTaskId =
                jdbcTemplate.queryForObject(
                        "INSERT INTO tasks (group_id, title, completed) VALUES (?, 'Tarea Target',"
                                + " false) RETURNING id",
                        Long.class,
                        targetGroupId);
        Long preservedTaskId =
                jdbcTemplate.queryForObject(
                        "INSERT INTO tasks (group_id, title, completed) VALUES (?, 'Tarea"
                                + " Preservada', false) RETURNING id",
                        Long.class,
                        preservedGroupId);

        Long targetTagId =
                jdbcTemplate.queryForObject(
                        "INSERT INTO tags (group_id, name) VALUES (?, 'urgente') RETURNING id",
                        Long.class,
                        targetGroupId);
        Long preservedTagId =
                jdbcTemplate.queryForObject(
                        "INSERT INTO tags (group_id, name) VALUES (?, 'casa') RETURNING id",
                        Long.class,
                        preservedGroupId);

        jdbcTemplate.update(
                "INSERT INTO task_tags (task_id, tag_id) VALUES (?, ?)", targetTaskId, targetTagId);
        jdbcTemplate.update(
                "INSERT INTO task_tags (task_id, tag_id) VALUES (?, ?)",
                preservedTaskId,
                preservedTagId);

        Timestamp execTime = Timestamp.from(Instant.now().plusSeconds(3600));
        jdbcTemplate.update(
                "INSERT INTO scheduled_tasks (task_name, task_instance, execution_time, picked,"
                        + " version) VALUES ('task-due-notification', ?, ?, false, 1)",
                "task-due-" + targetTaskId,
                execTime);
        jdbcTemplate.update(
                "INSERT INTO scheduled_tasks (task_name, task_instance, execution_time, picked,"
                        + " version) VALUES ('task-due-notification', ?, ?, false, 1)",
                "task-due-" + preservedTaskId,
                execTime);

        // Delete target group -> 204 No Content
        mockMvc.perform(
                        delete("/api/v1/groups/" + targetGroupId)
                                .cookie(user.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNoContent());

        // Verify target group & all cascaded rows + target scheduled_tasks are deleted
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM groups WHERE id = ?",
                                Integer.class,
                                targetGroupId))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM group_memberships WHERE group_id = ?",
                                Integer.class,
                                targetGroupId))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM tasks WHERE group_id = ?",
                                Integer.class,
                                targetGroupId))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM tags WHERE group_id = ?",
                                Integer.class,
                                targetGroupId))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM task_tags WHERE task_id = ?",
                                Integer.class,
                                targetTaskId))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM scheduled_tasks WHERE task_instance = ?",
                                Integer.class,
                                "task-due-" + targetTaskId))
                .isZero();

        // Verify preserved group & all its rows + scheduled_tasks remain intact
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM groups WHERE id = ?",
                                Integer.class,
                                preservedGroupId))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM group_memberships WHERE group_id = ?",
                                Integer.class,
                                preservedGroupId))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM tasks WHERE id = ?",
                                Integer.class,
                                preservedTaskId))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM tags WHERE id = ?",
                                Integer.class,
                                preservedTagId))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM task_tags WHERE task_id = ?",
                                Integer.class,
                                preservedTaskId))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM scheduled_tasks WHERE task_instance = ?",
                                Integer.class,
                                "task-due-" + preservedTaskId))
                .isEqualTo(1);
    }
}
