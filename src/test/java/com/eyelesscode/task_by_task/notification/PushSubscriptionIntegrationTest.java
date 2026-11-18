package com.eyelesscode.task_by_task.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eyelesscode.task_by_task.shared.AbstractIntegrationTest;
import com.eyelesscode.task_by_task.shared.ratelimit.RateLimitService;
import com.eyelesscode.task_by_task.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
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

class PushSubscriptionIntegrationTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-11-18T12:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-18T13:00:00Z");

    private static String validP256dh1;
    private static String validP256dh2;
    private static String validAuth1;
    private static String validAuth2;

    @Autowired private WebApplicationContext context;
    @Autowired private SessionRepositoryFilter<? extends Session> sessionRepositoryFilter;
    @Autowired private UserRepository userRepository;
    @Autowired private PushSubscriptionRepository pushSubscriptionRepository;
    @Autowired private RateLimitService rateLimitService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private Clock clock;

    private MockMvc mockMvc;

    @BeforeAll
    static void initKeys() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));

        KeyPair kp1 = kpg.generateKeyPair();
        validP256dh1 =
                WebPushCryptoUtils.encodeBase64UrlNoPadding(
                        WebPushCryptoUtils.encodeUncompressedPublicKey(
                                (ECPublicKey) kp1.getPublic()));
        KeyPair kp2 = kpg.generateKeyPair();
        validP256dh2 =
                WebPushCryptoUtils.encodeBase64UrlNoPadding(
                        WebPushCryptoUtils.encodeUncompressedPublicKey(
                                (ECPublicKey) kp2.getPublic()));

        byte[] a1 = new byte[16];
        a1[0] = 1;
        validAuth1 = WebPushCryptoUtils.encodeBase64UrlNoPadding(a1);

        byte[] a2 = new byte[16];
        a2[0] = 2;
        validAuth2 = WebPushCryptoUtils.encodeBase64UrlNoPadding(a2);
    }

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
            "GET /push/vapid-public-key returns {enabled: false, publicKey: null} when disabled in"
                    + " test profile (NTF-REQ-01)")
    void getVapidPublicKeyReturnsDisabledStateWithExplicitNullPublicKey() throws Exception {
        AuthenticatedUser user = registerAndGetSession("vapid@example.com", "vapiduser");

        MvcResult res =
                mockMvc.perform(
                                get("/api/v1/notifications/push/vapid-public-key")
                                        .cookie(user.sessionCookie()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.enabled").value(false))
                        .andExpect(jsonPath("$.publicKey").value(Matchers.nullValue()))
                        .andReturn();

        JsonNode root = objectMapper.readTree(res.getResponse().getContentAsString());
        assertThat(root.has("publicKey")).isTrue();
        assertThat(root.get("publicKey").isNull()).isTrue();
    }

    @Test
    @DisplayName(
            "POST /push/subscriptions: 201 insert, 200 idempotent same-user update, 200"
                    + " shared-browser rebind (EC-02), and multi-device")
    void registerUpdateRebindAndMultiDeviceSubscriptions() throws Exception {
        AuthenticatedUser userA = registerAndGetSession("suba@example.com", "subauser");
        AuthenticatedUser userB = registerAndGetSession("subb@example.com", "subbuser");

        String endpoint1 = "https://fcm.googleapis.com/fcm/send/device-1";
        String endpoint2 = "https://updates.push.services.mozilla.com/wpush/v2/device-2";

        // 1. User A registers endpoint1 (with surrounding spaces & extra W3C fields) -> 201 Created
        String createBody =
                String.format(
                        """
                        {
                          "id": 99999,
                          "userId": 88888,
                          "createdAt": "2020-01-01T00:00:00Z",
                          "expirationTime": null,
                          "endpoint": "  %s  ",
                          "keys": {
                            "p256dh": " %s ",
                            "auth": " %s "
                          }
                        }
                        """,
                        endpoint1, validP256dh1, validAuth1);

        MvcResult createRes =
                mockMvc.perform(
                                post("/api/v1/notifications/push/subscriptions")
                                        .cookie(userA.sessionCookie())
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(createBody))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.id").value(Matchers.not(99999)))
                        .andExpect(jsonPath("$.endpoint").value(endpoint1))
                        .andExpect(jsonPath("$.createdAt").value(T0.toString()))
                        .andExpect(jsonPath("$.p256dh").doesNotExist())
                        .andExpect(jsonPath("$.auth").doesNotExist())
                        .andExpect(jsonPath("$.keys").doesNotExist())
                        .andReturn();

        long subId =
                objectMapper
                        .readTree(createRes.getResponse().getContentAsString())
                        .get("id")
                        .asLong();

        // 2. Same user A re-registers endpoint1 with rotated keys -> 200 OK, preserves createdAt
        setClock(T1);
        String updateBody =
                String.format(
                        """
                        {
                          "endpoint": "%s",
                          "keys": {
                            "p256dh": "%s",
                            "auth": "%s"
                          }
                        }
                        """,
                        endpoint1, validP256dh2, validAuth2);

        mockMvc.perform(
                        post("/api/v1/notifications/push/subscriptions")
                                .cookie(userA.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(subId))
                .andExpect(jsonPath("$.endpoint").value(endpoint1))
                .andExpect(jsonPath("$.createdAt").value(T0.toString()));

        PushSubscription updatedSub =
                pushSubscriptionRepository.findByEndpoint(endpoint1).orElseThrow();
        assertThat(updatedSub.getP256dh()).isEqualTo(validP256dh2);
        assertThat(updatedSub.getAuth()).isEqualTo(validAuth2);

        // 3. User A registers second device endpoint2 -> 201 Created (multi-device NTF-REQ-05)
        mockMvc.perform(
                        post("/api/v1/notifications/push/subscriptions")
                                .cookie(userA.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        String.format(
                                                """
                                                {
                                                  "endpoint": "%s",
                                                  "keys": {
                                                    "p256dh": "%s",
                                                    "auth": "%s"
                                                  }
                                                }
                                                """,
                                                endpoint2, validP256dh1, validAuth1)))
                .andExpect(status().isCreated());
        assertThat(pushSubscriptionRepository.findAllByUserId(userA.userId())).hasSize(2);

        // 4. Shared browser: User B registers endpoint1 -> 200 OK, rebinds to User B and updates
        // createdAt
        mockMvc.perform(
                        post("/api/v1/notifications/push/subscriptions")
                                .cookie(userB.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(subId))
                .andExpect(jsonPath("$.createdAt").value(T1.toString()));

        assertThat(pushSubscriptionRepository.findAllByUserId(userA.userId())).hasSize(1);
        assertThat(pushSubscriptionRepository.findAllByUserId(userB.userId())).hasSize(1);
    }

    @Test
    @DisplayName(
            "DELETE by ID (404->403->204), DELETE ?endpoint= (400/idempotent/EC-08), and POST"
                    + " /auth/logout regression")
    void deleteByIdDeleteByEndpointAndLogoutCompatibility() throws Exception {
        AuthenticatedUser userA = registerAndGetSession("dela@example.com", "delauser");
        AuthenticatedUser userB = registerAndGetSession("delb@example.com", "delbuser");

        String epA1 = "https://fcm.googleapis.com/fcm/send/a1";
        String epA2 = "https://fcm.googleapis.com/fcm/send/a2";
        String epB1 = "https://fcm.googleapis.com/fcm/send/b1";

        long subA1Id = registerEndpoint(userA.sessionCookie(), epA1);
        long subA2Id = registerEndpoint(userA.sessionCookie(), epA2);
        long subB1Id = registerEndpoint(userB.sessionCookie(), epB1);

        // DELETE /{subscriptionId}: 404 not found -> 403 foreign -> 204 owner
        mockMvc.perform(
                        delete("/api/v1/notifications/push/subscriptions/{id}", 999999L)
                                .cookie(userA.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Suscripción push no encontrada"));

        mockMvc.perform(
                        delete("/api/v1/notifications/push/subscriptions/{id}", subB1Id)
                                .cookie(userA.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(
                        jsonPath("$.detail").value("No tiene permisos para realizar esta acción"));

        mockMvc.perform(
                        delete("/api/v1/notifications/push/subscriptions/{id}", subA1Id)
                                .cookie(userA.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(pushSubscriptionRepository.findById(subA1Id)).isEmpty();

        // DELETE ?endpoint=: 400 when missing or blank
        mockMvc.perform(
                        delete("/api/v1/notifications/push/subscriptions")
                                .cookie(userA.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "El parámetro 'endpoint' es obligatorio y no puede estar"
                                                + " vacío"));

        mockMvc.perform(
                        delete("/api/v1/notifications/push/subscriptions")
                                .param("endpoint", "   ")
                                .cookie(userA.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"));

        // EC-08: User A calling DELETE ?endpoint=epB1 returns 204 without touching User B's
        // subscription
        mockMvc.perform(
                        delete("/api/v1/notifications/push/subscriptions")
                                .param("endpoint", "  " + epB1 + " ")
                                .cookie(userA.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(pushSubscriptionRepository.findById(subB1Id)).isPresent();

        // User B calling DELETE ?endpoint=epB1 deletes it and repeated call is idempotent 204
        mockMvc.perform(
                        delete("/api/v1/notifications/push/subscriptions")
                                .param("endpoint", " " + epB1 + " ")
                                .cookie(userB.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(pushSubscriptionRepository.findById(subB1Id)).isEmpty();

        mockMvc.perform(
                        delete("/api/v1/notifications/push/subscriptions")
                                .param("endpoint", epB1)
                                .cookie(userB.sessionCookie())
                                .with(csrf()))
                .andExpect(status().isNoContent());

        // Regression with POST /api/v1/auth/logout (NTF-REQ-10)
        assertThat(pushSubscriptionRepository.findById(subA2Id)).isPresent();
        mockMvc.perform(
                        post("/api/v1/auth/logout")
                                .cookie(userA.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"endpoint\": \"" + epA2 + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(pushSubscriptionRepository.findById(subA2Id)).isEmpty();
    }

    @Test
    @DisplayName(
            "Validation 400 invalidParams, 401 unauthenticated, and 403 missing CSRF (NTF-REQ-06,"
                    + " 27, 28)")
    void validationAuthAndCsrfContracts() throws Exception {
        AuthenticatedUser user = registerAndGetSession("secsub@example.com", "secsubuser");

        // 401 unauthenticated
        mockMvc.perform(get("/api/v1/notifications/push/vapid-public-key"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(
                        post("/api/v1/notifications/push/subscriptions")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnauthorized());

        // 403 missing CSRF
        mockMvc.perform(
                        post("/api/v1/notifications/push/subscriptions")
                                .cookie(user.sessionCookie())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        delete("/api/v1/notifications/push/subscriptions/1")
                                .cookie(user.sessionCookie()))
                .andExpect(status().isForbidden());

        // 400 validation errors
        mockMvc.perform(
                        post("/api/v1/notifications/push/subscriptions")
                                .cookie(user.sessionCookie())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "endpoint": "ftp://bad.url",
                                          "keys": {
                                            "p256dh": "not-a-key",
                                            "auth": "not-16-bytes"
                                          }
                                        }
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Error de validación"))
                .andExpect(
                        jsonPath("$.invalidParams[*].name")
                                .value(Matchers.hasItems("endpoint", "keys.p256dh", "keys.auth")));
    }

    private long registerEndpoint(Cookie cookie, String endpoint) throws Exception {
        MvcResult res =
                mockMvc.perform(
                                post("/api/v1/notifications/push/subscriptions")
                                        .cookie(cookie)
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                String.format(
                                                        """
                                                        {
                                                          "endpoint": "%s",
                                                          "keys": {
                                                            "p256dh": "%s",
                                                            "auth": "%s"
                                                          }
                                                        }
                                                        """,
                                                        endpoint, validP256dh1, validAuth1)))
                        .andExpect(status().isCreated())
                        .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }
}
