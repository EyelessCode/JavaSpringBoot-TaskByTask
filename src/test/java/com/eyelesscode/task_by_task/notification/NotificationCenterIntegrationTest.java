package com.eyelesscode.task_by_task.notification;

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
import com.eyelesscode.task_by_task.user.User;
import com.eyelesscode.task_by_task.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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

class NotificationCenterIntegrationTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-11-18T12:00:00Z");

    @Autowired private WebApplicationContext context;
    @Autowired private SessionRepositoryFilter<? extends Session> sessionRepositoryFilter;
    @Autowired private UserRepository userRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationService notificationService;
    @Autowired private KeysetCursorCodec keysetCursorCodec;
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
        jdbcTemplate.execute("DELETE FROM SPRING_SESSION");
        userRepository.deleteAll();
    }

    private void setClock(Instant instant) {
        lenient().when(clock.instant()).thenReturn(instant);
        lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private record AuthenticatedUser(Long userId, User userEntity, Cookie sessionCookie) {}

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
        User user = userRepository.findById(userId).orElseThrow();
        return new AuthenticatedUser(userId, user, sessionCookie);
    }

    private Notification saveNotification(
            User user, String content, boolean read, Instant createdAt) {
        return notificationRepository.save(
                new Notification(
                        user,
                        NotificationEventType.TASK_DUE,
                        content,
                        read,
                        createdAt.truncatedTo(ChronoUnit.MICROS)));
    }

    @Test
    @DisplayName(
            "GET /notifications: Keyset created_at DESC, id DESC tie-breaker, stability on new"
                    + " insert (EC-10), and unreadOnly")
    void keysetPaginationTieBreakingStabilityAndUnreadOnly() throws Exception {
        AuthenticatedUser user = registerAndGetSession("nc1@example.com", "ncuser1");
        AuthenticatedUser other = registerAndGetSession("nc2@example.com", "ncuser2");

        // Save foreign notification to verify isolation
        saveNotification(other.userEntity(), "Ajena", false, T0.plusSeconds(100));

        // n1 (oldest), n2 & n3 share exact createdAt (tie-broken by id DESC: n3 before n2), n4
        // (newest, read=true)
        Notification n1 = saveNotification(user.userEntity(), "N1", false, T0.plusSeconds(10));
        Notification n2 = saveNotification(user.userEntity(), "N2-tie", false, T0.plusSeconds(20));
        Notification n3 = saveNotification(user.userEntity(), "N3-tie", false, T0.plusSeconds(20));
        Notification n4 = saveNotification(user.userEntity(), "N4-read", true, T0.plusSeconds(30));

        // Page 1 (limit=2): returns [n4, n3]
        MvcResult page1 =
                mockMvc.perform(get("/api/v1/notifications?limit=2").cookie(user.sessionCookie()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.content.length()").value(2))
                        .andExpect(jsonPath("$.content[0].id").value(n4.getId()))
                        .andExpect(jsonPath("$.content[0].read").value(true))
                        .andExpect(jsonPath("$.content[1].id").value(n3.getId()))
                        .andExpect(jsonPath("$.hasNext").value(true))
                        .andReturn();

        String cursor1 =
                objectMapper
                        .readTree(page1.getResponse().getContentAsString())
                        .get("nextCursor")
                        .asString();

        // EC-10: Insert a brand-new notification n5 BEFORE fetching Page 2 -> Page 2 still starts
        // cleanly at n2!
        saveNotification(user.userEntity(), "N5-concurrent", false, T0.plusSeconds(40));

        mockMvc.perform(
                        get("/api/v1/notifications")
                                .param("limit", "2")
                                .param("cursor", cursor1)
                                .cookie(user.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(n2.getId()))
                .andExpect(jsonPath("$.content[1].id").value(n1.getId()))
                .andExpect(jsonPath("$.hasNext").value(false));

        // unreadOnly=true excludes n4
        mockMvc.perform(
                        get("/api/v1/notifications")
                                .param("unreadOnly", "true")
                                .cookie(user.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(4));
    }

    @Test
    @DisplayName(
            "unread-count, PATCH /{id}/read idempotent, PATCH /read-all, DELETE /{id}, and 404->403"
                    + " precedence (EC-08)")
    void unreadCountMarkReadMarkAllAndDeleteWithPrecedence() throws Exception {
        AuthenticatedUser owner = registerAndGetSession("own@example.com", "ownuser");
        AuthenticatedUser other = registerAndGetSession("oth@example.com", "othuser");

        Notification n1 = saveNotification(owner.userEntity(), "Uno", false, T0.plusSeconds(1));
        Notification n2 = saveNotification(owner.userEntity(), "Dos", false, T0.plusSeconds(2));
        Notification nOther =
                saveNotification(other.userEntity(), "Ajena", false, T0.plusSeconds(3));

        mockMvc.perform(get("/api/v1/notifications/unread-count").cookie(owner.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(2));

        // PATCH /{id}/read -> 200 OK, idempotent on second call
        mockMvc.perform(
                        patch("/api/v1/notifications/{id}/read", n1.getId())
                                .cookie(owner.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(n1.getId()))
                .andExpect(jsonPath("$.read").value(true));

        mockMvc.perform(
                        patch("/api/v1/notifications/{id}/read", n1.getId())
                                .cookie(owner.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));

        mockMvc.perform(get("/api/v1/notifications/unread-count").cookie(owner.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1));

        // PATCH /read-all -> updates remaining 1 unread notification for owner only (not other
        // user)
        mockMvc.perform(
                        patch("/api/v1/notifications/read-all")
                                .cookie(owner.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(1));

        // Second PATCH /read-all -> updatedCount: 0
        mockMvc.perform(
                        patch("/api/v1/notifications/read-all")
                                .cookie(owner.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(0));

        // Other user's unread count is untouched
        mockMvc.perform(get("/api/v1/notifications/unread-count").cookie(other.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1));

        // 404 -> 403 precedence on PATCH /{id}/read and DELETE /{id}
        mockMvc.perform(
                        patch("/api/v1/notifications/{id}/read", 999999L)
                                .cookie(owner.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Notificación no encontrada"));

        mockMvc.perform(
                        patch("/api/v1/notifications/{id}/read", nOther.getId())
                                .cookie(owner.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(
                        jsonPath("$.detail").value("No tiene permisos para realizar esta acción"));

        mockMvc.perform(
                        delete("/api/v1/notifications/{id}", 999999L)
                                .cookie(owner.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Notificación no encontrada"));

        mockMvc.perform(
                        delete("/api/v1/notifications/{id}", nOther.getId())
                                .cookie(owner.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isForbidden());

        // Valid DELETE /{id} -> 204
        mockMvc.perform(
                        delete("/api/v1/notifications/{id}", n2.getId())
                                .cookie(owner.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(notificationRepository.findById(n2.getId())).isEmpty();
    }

    @Test
    @DisplayName("30-day retention purge boundary (EC-11) and 400/401/403 error contracts")
    void retentionPurgeBoundaryAndErrorContracts() throws Exception {
        AuthenticatedUser user = registerAndGetSession("ret@example.com", "retuser");

        Instant cutoff = T0.minus(30, ChronoUnit.DAYS);
        // 1 microsecond before cutoff -> purged (both read and unread)
        Notification expiredUnread =
                saveNotification(
                        user.userEntity(), "ExpUnread", false, cutoff.minus(1, ChronoUnit.MICROS));
        Notification expiredRead =
                saveNotification(
                        user.userEntity(), "ExpRead", true, cutoff.minus(1, ChronoUnit.DAYS));
        // Exactly at cutoff and newer -> preserved
        Notification exactBoundary = saveNotification(user.userEntity(), "Boundary", false, cutoff);
        Notification recent = saveNotification(user.userEntity(), "Recent", true, T0);

        int deleted = notificationService.purgeExpiredNotifications();
        assertThat(deleted).isEqualTo(2);
        assertThat(notificationRepository.findById(expiredUnread.getId())).isEmpty();
        assertThat(notificationRepository.findById(expiredRead.getId())).isEmpty();
        assertThat(notificationRepository.findById(exactBoundary.getId())).isPresent();
        assertThat(notificationRepository.findById(recent.getId())).isPresent();

        // 400 invalid cursor (blank, corrupt, wrong type), invalid limit, invalid param type
        String groupCursor = keysetCursorCodec.encodeGroupCursor(true, T0, 1L);
        mockMvc.perform(get("/api/v1/notifications?cursor=").cookie(user.sessionCookie()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/notifications?cursor=corrupt").cookie(user.sessionCookie()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        get("/api/v1/notifications")
                                .param("cursor", groupCursor)
                                .cookie(user.sessionCookie()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/notifications?limit=0").cookie(user.sessionCookie()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/notifications?limit=51").cookie(user.sessionCookie()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        get("/api/v1/notifications?unreadOnly=notbool")
                                .cookie(user.sessionCookie()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        patch("/api/v1/notifications/abc/read")
                                .cookie(user.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isBadRequest());

        // 401 unauthenticated & 403 missing CSRF
        mockMvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/notifications/unread-count"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/notifications/read-all").cookie(user.sessionCookie()))
                .andExpect(status().isForbidden());
    }
}
