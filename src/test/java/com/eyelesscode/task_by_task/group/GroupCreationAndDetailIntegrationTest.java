package com.eyelesscode.task_by_task.group;

import static org.assertj.core.api.Assertions.assertThat;
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

class GroupCreationAndDetailIntegrationTest extends AbstractIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private SessionRepositoryFilter<? extends Session> sessionRepositoryFilter;
    @Autowired private UserRepository userRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupMembershipRepository groupMembershipRepository;
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

    @Test
    @DisplayName(
            "POST /api/v1/groups crea un grupo individual (201 Created), recorta espacios, ignora"
                + " isPersonal=true en JSON y registra membresía CREATOR (GRP-REQ-01, GRP-REQ-02,"
                + " EC-01, EC-11)")
    void shouldCreateGroupTrimNameIgnoreIsPersonalAndCreateMembership() throws Exception {
        AuthenticatedUser user = registerAndGetSession("creator@example.com", "creatoruser");

        String payload =
                """
                {
                  "name": "   Universidad   ",
                  "isPersonal": true
                }
                """;

        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/groups")
                                        .cookie(user.sessionCookie())
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.id").isNumber())
                        .andExpect(jsonPath("$.name").value("Universidad"))
                        .andExpect(jsonPath("$.isPersonal").value(false))
                        .andExpect(jsonPath("$.creatorId").value(user.userId()))
                        .andExpect(jsonPath("$.createdAt").isNotEmpty())
                        .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                        .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        Long groupId = root.get("id").asLong();

        List<GroupMembership> memberships = groupMembershipRepository.findByUserId(user.userId());
        assertThat(memberships)
                .anySatisfy(
                        m -> {
                            assertThat(m.getGroup().getId()).isEqualTo(groupId);
                            assertThat(m.getRole()).isEqualTo("CREATOR");
                            assertThat(m.getJoinedAt()).isNotNull();
                        });
    }

    @Test
    @DisplayName(
            "POST /api/v1/groups permite crear múltiples grupos con nombres duplicados e incluso"
                    + " llamados 'Personal' con isPersonal=false (GRP-REQ-03, EC-03)")
    void shouldAllowDuplicateGroupNamesAndSecondaryPersonalNamedGroup() throws Exception {
        AuthenticatedUser user = registerAndGetSession("dup@example.com", "dupuser");

        mockMvc.perform(
                        post("/api/v1/groups")
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"Trabajo\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Trabajo"))
                .andExpect(jsonPath("$.isPersonal").value(false));

        mockMvc.perform(
                        post("/api/v1/groups")
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"Trabajo\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Trabajo"))
                .andExpect(jsonPath("$.isPersonal").value(false));

        mockMvc.perform(
                        post("/api/v1/groups")
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"Personal\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Personal"))
                .andExpect(jsonPath("$.isPersonal").value(false));
    }

    @Test
    @DisplayName(
            "POST /api/v1/groups rechaza con HTTP 400 e invalidParams nombres en blanco, menores a"
                    + " 3 caracteres tras trim o mayores a 30 caracteres (GRP-REQ-04, GRP-REQ-05,"
                    + " EC-01)")
    void shouldRejectInvalidGroupNamesWith400AndInvalidParams() throws Exception {
        AuthenticatedUser user = registerAndGetSession("val@example.com", "valuser");

        // Blank name
        mockMvc.perform(
                        post("/api/v1/groups")
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Error de validación"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.invalidParams[0].name").value("name"));

        // Less than 3 chars after trim ("  ab  " -> "ab")
        mockMvc.perform(
                        post("/api/v1/groups")
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"  ab  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Error de validación"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.invalidParams[0].name").value("name"))
                .andExpect(
                        jsonPath("$.invalidParams[0].reason")
                                .value("El nombre del grupo debe tener entre 3 y 30 caracteres"));

        // Greater than 30 chars (31 chars)
        String name31 = "A".repeat(31);
        mockMvc.perform(
                        post("/api/v1/groups")
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"" + name31 + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Error de validación"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.invalidParams[0].name").value("name"))
                .andExpect(
                        jsonPath("$.invalidParams[0].reason")
                                .value("El nombre del grupo debe tener entre 3 y 30 caracteres"));
    }

    @Test
    @DisplayName(
            "POST /api/v1/groups rechaza JSON malformado con 400, falta de sesión con 401 y falta"
                    + " de CSRF con 403 (GRP-REQ-23, GRP-REQ-24, GRP-REQ-25, EC-10)")
    void shouldRejectMalformedJsonUnauthenticatedAndMissingCsrfOnCreate() throws Exception {
        AuthenticatedUser user = registerAndGetSession("sec@example.com", "secuser");

        // Malformed JSON -> 400
        mockMvc.perform(
                        post("/api/v1/groups")
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{malformed-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "El cuerpo o parámetro de la solicitud no tiene un formato"
                                                + " válido"));

        // Unauthenticated -> 401
        mockMvc.perform(
                        post("/api/v1/groups")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"Valido\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("No autenticado"))
                .andExpect(jsonPath("$.status").value(401));

        // Missing CSRF -> 403
        mockMvc.perform(
                        post("/api/v1/groups")
                                .cookie(user.sessionCookie())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"Valido\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Acceso denegado"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName(
            "GET /api/v1/groups/{groupId} retorna 200 OK para el grupo Personal y grupos"
                    + " individuales propios (GRP-REQ-11)")
    void shouldReturnOwnPersonalAndIndividualGroupDetails() throws Exception {
        AuthenticatedUser user = registerAndGetSession("owner@example.com", "owneruser");

        Group personalGroup =
                groupRepository.findByCreatorIdAndIsPersonalTrue(user.userId()).orElseThrow();

        mockMvc.perform(get("/api/v1/groups/" + personalGroup.getId()).cookie(user.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(personalGroup.getId()))
                .andExpect(jsonPath("$.name").value("Personal"))
                .andExpect(jsonPath("$.isPersonal").value(true))
                .andExpect(jsonPath("$.creatorId").value(user.userId()));

        MvcResult createResult =
                mockMvc.perform(
                                post("/api/v1/groups")
                                        .cookie(user.sessionCookie())
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\": \"Proyecto X\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();

        Long createdGroupId =
                objectMapper
                        .readTree(createResult.getResponse().getContentAsString())
                        .get("id")
                        .asLong();

        mockMvc.perform(get("/api/v1/groups/" + createdGroupId).cookie(user.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(createdGroupId))
                .andExpect(jsonPath("$.name").value("Proyecto X"))
                .andExpect(jsonPath("$.isPersonal").value(false))
                .andExpect(jsonPath("$.creatorId").value(user.userId()));
    }

    @Test
    @DisplayName(
            "GET /api/v1/groups/{groupId} rechaza con 404 si no existe, 403 si pertenece a otro"
                + " usuario, 400 si el ID no es numérico y 401 sin sesión (GRP-REQ-12, GRP-REQ-13,"
                + " EC-05, EC-10)")
    void shouldRejectGetGroupWhenNotFoundForbiddenInvalidIdOrUnauthenticated() throws Exception {
        AuthenticatedUser userA = registerAndGetSession("usera@example.com", "usera");
        AuthenticatedUser userB = registerAndGetSession("userb@example.com", "userb");

        Group userBPersonal =
                groupRepository.findByCreatorIdAndIsPersonalTrue(userB.userId()).orElseThrow();

        // 404 Not Found
        mockMvc.perform(get("/api/v1/groups/999999").cookie(userA.sessionCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso no encontrado"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Grupo no encontrado"));

        // 403 Forbidden (other user's group)
        mockMvc.perform(
                        get("/api/v1/groups/" + userBPersonal.getId())
                                .cookie(userA.sessionCookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Acceso denegado"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(
                        jsonPath("$.detail").value("No tiene permisos para realizar esta acción"));

        // 400 Bad Request (non-numeric ID)
        mockMvc.perform(get("/api/v1/groups/abc").cookie(userA.sessionCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "El cuerpo o parámetro de la solicitud no tiene un formato"
                                                + " válido"));

        // 401 Unauthorized
        mockMvc.perform(get("/api/v1/groups/" + userBPersonal.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("No autenticado"))
                .andExpect(jsonPath("$.status").value(401));
    }
}
