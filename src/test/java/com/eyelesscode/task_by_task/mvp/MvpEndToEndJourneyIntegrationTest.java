package com.eyelesscode.task_by_task.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eyelesscode.task_by_task.notification.Notification;
import com.eyelesscode.task_by_task.notification.NotificationEventType;
import com.eyelesscode.task_by_task.notification.NotificationRepository;
import com.eyelesscode.task_by_task.notification.NotificationSchedulerConfig;
import com.eyelesscode.task_by_task.notification.PushSubscriptionRepository;
import com.eyelesscode.task_by_task.shared.AbstractIntegrationTest;
import com.eyelesscode.task_by_task.shared.ratelimit.RateLimitService;
import com.eyelesscode.task_by_task.shared.security.TrustedForwardedHeaderFilter;
import com.eyelesscode.task_by_task.task.TaskDueSchedulePort;
import com.eyelesscode.task_by_task.user.User;
import com.eyelesscode.task_by_task.user.UserRepository;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.test.web.support.WebTestUtils;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.session.Session;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(
        properties = {"db-scheduler.enabled=true", "db-scheduler.polling-interval=200ms"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MvpEndToEndJourneyIntegrationTest extends AbstractIntegrationTest {

    private static final String VAPID_PUBLIC_KEY;
    private static final String VAPID_PRIVATE_KEY;
    private static final String CLIENT_P256DH;
    private static final String CLIENT_AUTH;

    private static HttpServer pushServer;
    private static String pushBaseUrl;
    private static final AtomicReference<byte[]> LAST_PUSH_BODY = new AtomicReference<>();
    private static final AtomicReference<String> LAST_PUSH_AUTH = new AtomicReference<>();
    private static final AtomicReference<String> LAST_PUSH_ENCODING = new AtomicReference<>();
    private static final AtomicInteger PUSH_410_HITS = new AtomicInteger();

    static {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));

            KeyPair vapidKeyPair = kpg.generateKeyPair();
            VAPID_PUBLIC_KEY =
                    base64Url(encodeUncompressedPublicKey((ECPublicKey) vapidKeyPair.getPublic()));
            VAPID_PRIVATE_KEY =
                    base64Url(toFixedLength(((ECPrivateKey) vapidKeyPair.getPrivate()).getS(), 32));

            KeyPair clientKeyPair = kpg.generateKeyPair();
            CLIENT_P256DH =
                    base64Url(encodeUncompressedPublicKey((ECPublicKey) clientKeyPair.getPublic()));
            byte[] authBytes = new byte[16];
            new SecureRandom().nextBytes(authBytes);
            CLIENT_AUTH = base64Url(authBytes);
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void registerVapidProperties(DynamicPropertyRegistry registry) {
        registry.add("app.web-push.enabled", () -> "true");
        registry.add("app.web-push.vapid.public-key", () -> VAPID_PUBLIC_KEY);
        registry.add("app.web-push.vapid.private-key", () -> VAPID_PRIVATE_KEY);
        registry.add("app.web-push.vapid.subject", () -> "mailto:mvp-e2e@taskbytask.app");
    }

    @BeforeAll
    static void startEmbeddedPushServer() throws Exception {
        pushServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pushBaseUrl = "http://127.0.0.1:" + pushServer.getAddress().getPort();

        pushServer.createContext(
                "/push/201",
                exchange -> {
                    LAST_PUSH_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    LAST_PUSH_ENCODING.set(
                            exchange.getRequestHeaders().getFirst("Content-Encoding"));
                    LAST_PUSH_BODY.set(exchange.getRequestBody().readAllBytes());
                    exchange.sendResponseHeaders(201, -1);
                    exchange.close();
                });
        pushServer.createContext(
                "/push/410",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    PUSH_410_HITS.incrementAndGet();
                    exchange.sendResponseHeaders(410, -1);
                    exchange.close();
                });
        pushServer.createContext(
                "/push/device2",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(201, -1);
                    exchange.close();
                });
        pushServer.start();
    }

    @AfterAll
    static void stopEmbeddedPushServer() {
        if (pushServer != null) {
            pushServer.stop(0);
        }
    }

    @Autowired private WebApplicationContext context;
    @Autowired private TrustedForwardedHeaderFilter trustedForwardedHeaderFilter;
    @Autowired private SessionRepositoryFilter<? extends Session> sessionRepositoryFilter;
    @Autowired private CookieCsrfTokenRepository cookieCsrfTokenRepository;
    @Autowired private RateLimitService rateLimitService;
    @Autowired private Scheduler scheduler;
    @Autowired private UserRepository userRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private PushSubscriptionRepository pushSubscriptionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        WebTestUtils.setCsrfTokenRepository(
                new MockHttpServletRequest(context.getServletContext()), cookieCsrfTokenRepository);
        mockMvc =
                MockMvcBuilders.webAppContextSetup(context)
                        .addFilters(trustedForwardedHeaderFilter, sessionRepositoryFilter)
                        .apply(springSecurity())
                        .build();

        LAST_PUSH_BODY.set(null);
        LAST_PUSH_AUTH.set(null);
        LAST_PUSH_ENCODING.set(null);
        PUSH_410_HITS.set(0);
        rateLimitService.clear();
        jdbcTemplate.update(
                "DELETE FROM scheduled_tasks WHERE task_name = ?", TaskDueSchedulePort.TASK_NAME);
        jdbcTemplate.execute("DELETE FROM SPRING_SESSION");
        userRepository.deleteAll();
    }

    private record CsrfState(Cookie cookie, String token) {}

    private CsrfState fetchCsrf() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/api/v1/auth/csrf"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
                        .andReturn();
        Cookie xsrfCookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrfCookie).isNotNull();
        String token =
                objectMapper
                        .readTree(result.getResponse().getContentAsString())
                        .get("token")
                        .asString();
        return new CsrfState(xsrfCookie, token);
    }

    @Test
    @DisplayName(
            "Recorrido E2E completo del MVP sobre PostgreSQL real, Web Push RFC 8291/8292 y"
                    + " db-scheduler con 0 filas huérfanas tras eliminar cuenta"
                    + " (HRD-REQ-21, EC-08)")
    void shouldCompleteFullMvpUserJourneyEndToEndWithZeroOrphanRows() throws Exception {
        // =====================================================================
        // 1. GET /api/v1/auth/csrf -> POST /api/v1/auth/register
        // =====================================================================
        CsrfState csrf = fetchCsrf();

        MvcResult registerResult =
                mockMvc.perform(
                                post("/api/v1/auth/register")
                                        .cookie(csrf.cookie())
                                        .header("X-XSRF-TOKEN", csrf.token())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "email": "e2e@taskbytask.app",
                                                  "username": "E2eUser2026",
                                                  "displayName": "Usuario E2E",
                                                  "password": "Password123",
                                                  "timezone": "UTC"
                                                }
                                                """))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.user.email").value("e2e@taskbytask.app"))
                        .andExpect(jsonPath("$.recoveryCodes.length()").value(8))
                        .andReturn();

        Cookie sessionCookie = registerResult.getResponse().getCookie("SESSION");
        assertThat(sessionCookie).isNotNull();
        JsonNode registerJson =
                objectMapper.readTree(registerResult.getResponse().getContentAsString());
        Long userId = registerJson.get("user").get("id").asLong();
        List<String> recoveryCodes = new ArrayList<>();
        registerJson.get("recoveryCodes").forEach(n -> recoveryCodes.add(n.asString()));
        assertThat(recoveryCodes).hasSize(8);

        // =====================================================================
        // 2. POST /api/v1/auth/login (actualiza timezone) -> PATCH /api/v1/users/me (12H)
        // =====================================================================
        rateLimitService.clear();
        MvcResult loginResult =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .cookie(csrf.cookie())
                                        .header("X-XSRF-TOKEN", csrf.token())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "email": "e2e@taskbytask.app",
                                                  "password": "Password123",
                                                  "timezone": "America/Lima"
                                                }
                                                """))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.timezone").value("America/Lima"))
                        .andReturn();
        sessionCookie = loginResult.getResponse().getCookie("SESSION");
        assertThat(sessionCookie).isNotNull();

        mockMvc.perform(
                        patch("/api/v1/users/me")
                                .cookie(sessionCookie, csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"timeFormat\": \"12H\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeFormat").value("12H"));

        // =====================================================================
        // 3. Groups: crear, paginación Keyset (Personal primero), renombrar, bloquear borrado 409
        // =====================================================================
        Long workGroupId =
                objectMapper
                        .readTree(
                                mockMvc.perform(
                                                post("/api/v1/groups")
                                                        .cookie(sessionCookie, csrf.cookie())
                                                        .header("X-XSRF-TOKEN", csrf.token())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content("{\"name\": \"Trabajo\"}"))
                                        .andExpect(status().isCreated())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get("id")
                        .asLong();

        Long studyGroupId =
                objectMapper
                        .readTree(
                                mockMvc.perform(
                                                post("/api/v1/groups")
                                                        .cookie(sessionCookie, csrf.cookie())
                                                        .header("X-XSRF-TOKEN", csrf.token())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content("{\"name\": \"Estudios\"}"))
                                        .andExpect(status().isCreated())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get("id")
                        .asLong();

        JsonNode groupsPage1 =
                objectMapper.readTree(
                        mockMvc.perform(
                                        get("/api/v1/groups")
                                                .param("limit", "2")
                                                .cookie(sessionCookie))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.content[0].isPersonal").value(true))
                                .andExpect(jsonPath("$.content[0].name").value("Personal"))
                                .andExpect(jsonPath("$.hasNext").value(true))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        Long personalGroupId = groupsPage1.get("content").get(0).get("id").asLong();
        String nextGroupCursor = groupsPage1.get("nextCursor").asString();

        mockMvc.perform(
                        get("/api/v1/groups")
                                .param("cursor", nextGroupCursor)
                                .param("limit", "2")
                                .cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Estudios"))
                .andExpect(jsonPath("$.hasNext").value(false));

        mockMvc.perform(
                        patch("/api/v1/groups/{id}", personalGroupId)
                                .cookie(sessionCookie, csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"Mi Personal\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mi Personal"));

        mockMvc.perform(
                        delete("/api/v1/groups/{id}", personalGroupId)
                                .cookie(sessionCookie, csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflicto de estado"));

        // =====================================================================
        // 4. Tasks & Tags: fechas, etiquetas case-insensitive, PATCH, filtros, move, copy, detach
        //    vs delete, completion
        // =====================================================================
        ZoneId limaZone = ZoneId.of("America/Lima");
        LocalDate todayLima = LocalDate.now(limaZone);

        // 4a. Tarea solo fecha hoy (no vencida hasta 23:59:59.999 hora local) + etiquetas
        // duplicadas en distinto case/espacios
        JsonNode dateOnlyTask =
                objectMapper.readTree(
                        mockMvc.perform(
                                        post("/api/v1/groups/{groupId}/tasks", workGroupId)
                                                .cookie(sessionCookie, csrf.cookie())
                                                .header("X-XSRF-TOKEN", csrf.token())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {
                                                          "title": "Revisar arquitectura MVP",
                                                          "description": "Checklist final",
                                                          "dueDate": "%s",
                                                          "tags": ["Urgente", "  urgente  ", "Backend"]
                                                        }
                                                        """
                                                                .formatted(todayLima)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.overdue").value(false))
                                .andExpect(jsonPath("$.tags.length()").value(2))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        Long dateOnlyTaskId = dateOnlyTask.get("id").asLong();
        Long backendTagId = null;
        for (JsonNode tagNode : dateOnlyTask.get("tags")) {
            if ("Backend".equalsIgnoreCase(tagNode.get("name").asString())) {
                backendTagId = tagNode.get("id").asLong();
            }
        }
        assertThat(backendTagId).isNotNull();

        // 4b. Tarea sin fecha
        Long undatedTaskId =
                objectMapper
                        .readTree(
                                mockMvc.perform(
                                                post("/api/v1/groups/{groupId}/tasks", workGroupId)
                                                        .cookie(sessionCookie, csrf.cookie())
                                                        .header("X-XSRF-TOKEN", csrf.token())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                """
                                                                {
                                                                  "title": "Documentar despliegue MVP",
                                                                  "tags": ["Urgente"]
                                                                }
                                                                """))
                                        .andExpect(status().isCreated())
                                        .andExpect(jsonPath("$.overdue").value(false))
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get("id")
                        .asLong();

        // 4c. PATCH tarea
        mockMvc.perform(
                        patch("/api/v1/tasks/{id}", dateOnlyTaskId)
                                .cookie(sessionCookie, csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"description\": \"Checklist final verificado\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Checklist final verificado"));

        // 4d. Filtros combinados + Keyset pagination
        JsonNode taskPage1 =
                objectMapper.readTree(
                        mockMvc.perform(
                                        get("/api/v1/tasks")
                                                .param("groupIds", String.valueOf(workGroupId))
                                                .param("status", "PENDING")
                                                .param("search", "mvp")
                                                .param("limit", "1")
                                                .cookie(sessionCookie))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.content.length()").value(1))
                                .andExpect(jsonPath("$.hasNext").value(true))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        mockMvc.perform(
                        get("/api/v1/tasks")
                                .param("groupIds", String.valueOf(workGroupId))
                                .param("status", "PENDING")
                                .param("search", "mvp")
                                .param("cursor", taskPage1.get("nextCursor").asString())
                                .param("limit", "1")
                                .cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));

        // 4e. Move y Copy
        mockMvc.perform(
                        post("/api/v1/tasks/{id}/move", undatedTaskId)
                                .cookie(sessionCookie, csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"targetGroupId\": " + studyGroupId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(studyGroupId));

        mockMvc.perform(
                        post("/api/v1/tasks/{id}/copy", undatedTaskId)
                                .cookie(sessionCookie, csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"targetGroupId\": " + workGroupId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groupId").value(workGroupId));

        // 4f. Desasociar etiqueta de tarea vs eliminar etiqueta del grupo
        mockMvc.perform(
                        delete("/api/v1/tasks/{taskId}/tags/{tagId}", dateOnlyTaskId, backendTagId)
                                .cookie(sessionCookie, csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isNoContent());

        // La etiqueta sigue existiendo en el grupo tras desasociarla de la tarea
        mockMvc.perform(
                        get("/api/v1/groups/{groupId}/tags", workGroupId)
                                .param("query", "backend")
                                .cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));

        // Ahora se elimina del grupo por completo
        mockMvc.perform(
                        delete("/api/v1/groups/{groupId}/tags/{tagId}", workGroupId, backendTagId)
                                .cookie(sessionCookie, csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isNoContent());

        mockMvc.perform(
                        get("/api/v1/groups/{groupId}/tags", workGroupId)
                                .param("query", "backend")
                                .cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        // 4g. Completar tarea
        mockMvc.perform(
                        patch("/api/v1/tasks/{id}/completion", undatedTaskId)
                                .cookie(sessionCookie, csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"completed\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true));

        // =====================================================================
        // 5. Web Push, re-vinculación, db-scheduler TASK_DUE + 410 cleanup + Notification Center
        //    + retención > 30 días
        // =====================================================================
        mockMvc.perform(get("/api/v1/notifications/push/vapid-public-key").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.publicKey").value(VAPID_PUBLIC_KEY));

        String endpoint201 = pushBaseUrl + "/push/201";
        String endpoint410 = pushBaseUrl + "/push/410";
        String endpointDevice2 = pushBaseUrl + "/push/device2";

        // Registrar suscripción 201 y 410
        mockMvc.perform(
                        post("/api/v1/notifications/push/subscriptions")
                                .cookie(sessionCookie, csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(pushSubPayload(endpoint201)))
                .andExpect(status().isCreated());

        mockMvc.perform(
                        post("/api/v1/notifications/push/subscriptions")
                                .cookie(sessionCookie, csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(pushSubPayload(endpoint410)))
                .andExpect(status().isCreated());

        // Re-vinculación de navegador compartido: otro usuario reclama endpoint201 y luego
        // el usuario principal lo vuelve a reclamar (200 OK)
        rateLimitService.clear();
        MvcResult userBRegister =
                mockMvc.perform(
                                post("/api/v1/auth/register")
                                        .cookie(csrf.cookie())
                                        .header("X-XSRF-TOKEN", csrf.token())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "email": "sharedbrowser@taskbytask.app",
                                                  "username": "SharedUserB",
                                                  "displayName": "Usuario B",
                                                  "password": "Password123",
                                                  "timezone": "UTC"
                                                }
                                                """))
                        .andExpect(status().isCreated())
                        .andReturn();
        Cookie userBSession = userBRegister.getResponse().getCookie("SESSION");
        assertThat(userBSession).isNotNull();

        mockMvc.perform(
                        post("/api/v1/notifications/push/subscriptions")
                                .cookie(userBSession, csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(pushSubPayload(endpoint201)))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/notifications/push/subscriptions")
                                .cookie(sessionCookie, csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(pushSubPayload(endpoint201)))
                .andExpect(status().isOk());

        // Eliminar usuario B para que al final de la prueba el conteo de 0 filas sea global
        mockMvc.perform(
                        delete("/api/v1/users/me")
                                .cookie(userBSession, csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\": \"Password123\"}"))
                .andExpect(status().isNoContent());

        // Crear tarea con fecha+hora pasada (nace vencida -> overdue=true)
        ZonedDateTime twoMinutesAgoLima = ZonedDateTime.now(limaZone).minusMinutes(2);
        mockMvc.perform(
                        post("/api/v1/groups/{groupId}/tasks", workGroupId)
                                .cookie(sessionCookie, csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "title": "Tarea nacida vencida",
                                          "dueDate": "%s",
                                          "dueTime": "%s"
                                        }
                                        """
                                                .formatted(
                                                        twoMinutesAgoLima.toLocalDate(),
                                                        twoMinutesAgoLima
                                                                .toLocalTime()
                                                                .truncatedTo(ChronoUnit.SECONDS))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.overdue").value(true));

        // Crear tarea que vence en 2 segundos -> TaskService programa task-due-{id} en
        // db-scheduler y se dispara automáticamente
        ZonedDateTime twoSecondsAheadLima = ZonedDateTime.now(limaZone).plusSeconds(2);
        mockMvc.perform(
                        post("/api/v1/groups/{groupId}/tasks", workGroupId)
                                .cookie(sessionCookie, csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "title": "Enviar factura urgente",
                                          "dueDate": "%s",
                                          "dueTime": "%s"
                                        }
                                        """
                                                .formatted(
                                                        twoSecondsAheadLima.toLocalDate(),
                                                        twoSecondsAheadLima
                                                                .toLocalTime()
                                                                .truncatedTo(ChronoUnit.SECONDS))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.overdue").value(false));

        // Esperar a que db-scheduler ejecute el aviso, envíe push cifrado a /201 y limpie /410
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(150))
                .untilAsserted(
                        () -> {
                            assertThat(notificationRepository.countByUserIdAndReadFalse(userId))
                                    .isGreaterThanOrEqualTo(1L);
                            assertThat(LAST_PUSH_BODY.get()).isNotNull().isNotEmpty();
                            assertThat(LAST_PUSH_AUTH.get()).startsWith("vapid ");
                            assertThat(LAST_PUSH_ENCODING.get()).isEqualTo("aes128gcm");
                            assertThat(PUSH_410_HITS.get()).isGreaterThanOrEqualTo(1);
                            assertThat(pushSubscriptionRepository.findByEndpoint(endpoint410))
                                    .isEmpty();
                        });

        // Consultar unread-count, listado Keyset, mark-read, read-all y delete
        mockMvc.perform(get("/api/v1/notifications/unread-count").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.unreadCount")
                                .value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        JsonNode notificationsPage =
                objectMapper.readTree(
                        mockMvc.perform(
                                        get("/api/v1/notifications")
                                                .param("limit", "10")
                                                .cookie(sessionCookie))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.content[0].eventType").value("TASK_DUE"))
                                .andExpect(jsonPath("$.content[0].read").value(false))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        Long notificationId = notificationsPage.get("content").get(0).get("id").asLong();

        mockMvc.perform(
                        patch("/api/v1/notifications/{id}/read", notificationId)
                                .cookie(sessionCookie, csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));

        mockMvc.perform(
                        patch("/api/v1/notifications/read-all")
                                .cookie(sessionCookie, csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.updatedCount")
                                .value(org.hamcrest.Matchers.greaterThanOrEqualTo(0)));

        mockMvc.perform(get("/api/v1/notifications/unread-count").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));

        mockMvc.perform(
                        delete("/api/v1/notifications/{id}", notificationId)
                                .cookie(sessionCookie, csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isNoContent());

        // Retención > 30 días vía db-scheduler
        User mainUser = userRepository.findById(userId).orElseThrow();
        Notification expiredNotification =
                notificationRepository.save(
                        new Notification(
                                mainUser,
                                NotificationEventType.TASK_DUE,
                                "Notificación caducada",
                                false,
                                Instant.now().minus(31, ChronoUnit.DAYS)));

        scheduler.reschedule(
                TaskInstanceId.of(
                        NotificationSchedulerConfig.RETENTION_TASK_NAME, RecurringTask.INSTANCE),
                Instant.now().minusSeconds(1));

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(150))
                .untilAsserted(
                        () ->
                                assertThat(
                                                notificationRepository.findById(
                                                        expiredNotification.getId()))
                                        .isEmpty());

        // =====================================================================
        // 6. Logout selectivo, recuperación con código 1-uso, re-login y borrado de cuenta
        //    con 0 filas huérfanas en las 12 tablas de V1__init_schema.sql
        // =====================================================================
        mockMvc.perform(
                        post("/api/v1/notifications/push/subscriptions")
                                .cookie(sessionCookie, csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(pushSubPayload(endpointDevice2)))
                .andExpect(status().isCreated());

        // Logout selectivo: elimina solo endpointDevice2, conserva endpoint201
        mockMvc.perform(
                        post("/api/v1/auth/logout")
                                .cookie(sessionCookie, csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"endpoint\": \"" + endpointDevice2 + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(pushSubscriptionRepository.findByEndpoint(endpointDevice2)).isEmpty();
        assertThat(pushSubscriptionRepository.findByEndpoint(endpoint201)).isPresent();

        // Iniciar nueva sesión y luego ejecutar recover-password (debe invalidar todas las
        // sesiones y rechazar el reuso del código)
        rateLimitService.clear();
        Cookie preRecoverySession =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .cookie(csrf.cookie())
                                        .header("X-XSRF-TOKEN", csrf.token())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "email": "e2e@taskbytask.app",
                                                  "password": "Password123"
                                                }
                                                """))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getCookie("SESSION");
        assertThat(preRecoverySession).isNotNull();

        String usedCode = recoveryCodes.getFirst();
        mockMvc.perform(
                        post("/api/v1/auth/recover-password")
                                .cookie(csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "email": "e2e@taskbytask.app",
                                          "recoveryCode": "%s",
                                          "newPassword": "NewPassword456"
                                        }
                                        """
                                                .formatted(usedCode)))
                .andExpect(status().isOk());

        // Sesión previa invalidada globalmente
        mockMvc.perform(
                        get("/api/v1/users/me")
                                .cookie(preRecoverySession)
                                .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized());

        // Reuso del código rechazado con 400
        mockMvc.perform(
                        post("/api/v1/auth/recover-password")
                                .cookie(csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "email": "e2e@taskbytask.app",
                                          "recoveryCode": "%s",
                                          "newPassword": "AnotherPass789"
                                        }
                                        """
                                                .formatted(usedCode)))
                .andExpect(status().isBadRequest());

        // Re-login con nueva contraseña y eliminación total de la cuenta
        rateLimitService.clear();
        Cookie finalSession =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .cookie(csrf.cookie())
                                        .header("X-XSRF-TOKEN", csrf.token())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "email": "e2e@taskbytask.app",
                                                  "password": "NewPassword456"
                                                }
                                                """))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.remainingRecoveryCodes").value(7))
                        .andReturn()
                        .getResponse()
                        .getCookie("SESSION");
        assertThat(finalSession).isNotNull();

        mockMvc.perform(
                        delete("/api/v1/users/me")
                                .cookie(finalSession, csrf.cookie())
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\": \"NewPassword456\"}"))
                .andExpect(status().isNoContent());

        // Verificar 0 filas huérfanas en las 12 tablas de V1__init_schema.sql
        List<String> domainAndSessionTables =
                List.of(
                        "users",
                        "recovery_codes",
                        "groups",
                        "group_memberships",
                        "tags",
                        "tasks",
                        "task_tags",
                        "push_subscriptions",
                        "notifications",
                        "spring_session",
                        "spring_session_attributes");

        for (String table : domainAndSessionTables) {
            Integer count =
                    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            assertThat(count).as("Tabla %s debe quedar con 0 filas huérfanas", table).isZero();
        }

        Integer remainingDueJobs =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM scheduled_tasks WHERE task_name = ?",
                        Integer.class,
                        TaskDueSchedulePort.TASK_NAME);
        assertThat(remainingDueJobs)
                .as("No deben quedar jobs task-due-notification huérfanos en scheduled_tasks")
                .isZero();
    }

    private static String pushSubPayload(String endpoint) {
        return """
        {
          "endpoint": "%s",
          "keys": {
            "p256dh": "%s",
            "auth": "%s"
          }
        }
        """
                .formatted(endpoint, CLIENT_P256DH, CLIENT_AUTH);
    }

    private static byte[] encodeUncompressedPublicKey(ECPublicKey key) {
        ECPoint w = key.getW();
        byte[] x = toFixedLength(w.getAffineX(), 32);
        byte[] y = toFixedLength(w.getAffineY(), 32);
        byte[] uncompressed = new byte[65];
        uncompressed[0] = 0x04;
        System.arraycopy(x, 0, uncompressed, 1, 32);
        System.arraycopy(y, 0, uncompressed, 33, 32);
        return uncompressed;
    }

    private static byte[] toFixedLength(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        if (raw.length == length) {
            return raw;
        }
        byte[] result = new byte[length];
        if (raw.length > length) {
            System.arraycopy(raw, raw.length - length, result, 0, length);
        } else {
            System.arraycopy(raw, 0, result, length - raw.length, raw.length);
        }
        return result;
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
