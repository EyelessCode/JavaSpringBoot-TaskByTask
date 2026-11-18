package com.eyelesscode.task_by_task.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import com.eyelesscode.task_by_task.group.Group;
import com.eyelesscode.task_by_task.group.GroupRepository;
import com.eyelesscode.task_by_task.shared.AbstractIntegrationTest;
import com.eyelesscode.task_by_task.task.Task;
import com.eyelesscode.task_by_task.task.TaskRepository;
import com.eyelesscode.task_by_task.user.User;
import com.eyelesscode.task_by_task.user.UserRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

class DueNotificationDeliveryIntegrationTest extends AbstractIntegrationTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-18T14:00:00Z"); // 09:00 Bogota

    private static String vapidPublicKeyBase64Url;
    private static String vapidPrivateKeyBase64Url;
    private static byte[] vapidRawPublicBytes;

    private static KeyPair clientKeyPair;
    private static byte[] clientPublicBytes;
    private static String clientP256dh;
    private static byte[] clientAuthBytes;
    private static String clientAuth;

    private static HttpServer server;
    private static String baseUrl;
    private static final AtomicReference<byte[]> LAST_ENCRYPTED_BODY = new AtomicReference<>();
    private static final AtomicReference<String> LAST_AUTH_HEADER = new AtomicReference<>();

    static {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));

            KeyPair vapidKp = kpg.generateKeyPair();
            vapidRawPublicBytes =
                    WebPushCryptoUtils.encodeUncompressedPublicKey(
                            (ECPublicKey) vapidKp.getPublic());
            vapidPublicKeyBase64Url =
                    WebPushCryptoUtils.encodeBase64UrlNoPadding(vapidRawPublicBytes);
            vapidPrivateKeyBase64Url =
                    WebPushCryptoUtils.encodeBase64UrlNoPadding(
                            WebPushCryptoUtils.encodePrivateKeyScalar(
                                    (ECPrivateKey) vapidKp.getPrivate()));

            clientKeyPair = kpg.generateKeyPair();
            clientPublicBytes =
                    WebPushCryptoUtils.encodeUncompressedPublicKey(
                            (ECPublicKey) clientKeyPair.getPublic());
            clientP256dh = WebPushCryptoUtils.encodeBase64UrlNoPadding(clientPublicBytes);
            clientAuthBytes = new byte[16];
            new SecureRandom().nextBytes(clientAuthBytes);
            clientAuth = WebPushCryptoUtils.encodeBase64UrlNoPadding(clientAuthBytes);
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void registerVapidProperties(DynamicPropertyRegistry registry) {
        registry.add("app.web-push.enabled", () -> "true");
        registry.add("app.web-push.vapid.public-key", () -> vapidPublicKeyBase64Url);
        registry.add("app.web-push.vapid.private-key", () -> vapidPrivateKeyBase64Url);
        registry.add("app.web-push.vapid.subject", () -> "mailto:push@taskbytask.app");
    }

    @Autowired private DueNotificationJobService dueNotificationJobService;
    @Autowired private PushSubscriptionService pushSubscriptionService;
    @Autowired private UserRepository userRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private PushSubscriptionRepository pushSubscriptionRepository;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private Clock clock;

    @BeforeAll
    static void startHttpServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext(
                "/push/201",
                exchange -> {
                    LAST_AUTH_HEADER.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    LAST_ENCRYPTED_BODY.set(exchange.getRequestBody().readAllBytes());
                    exchange.sendResponseHeaders(201, -1);
                    exchange.close();
                });
        server.createContext(
                "/push/410",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(410, -1);
                    exchange.close();
                });
        server.createContext(
                "/push/404",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                });
        server.createContext(
                "/push/500",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                });
        server.start();
    }

    @AfterAll
    static void stopHttpServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void setUp() {
        lenient().when(clock.instant()).thenReturn(FIXED_NOW);
        lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        LAST_ENCRYPTED_BODY.set(null);
        LAST_AUTH_HEADER.set(null);
        userRepository.deleteAll();
    }

    @Test
    @DisplayName(
            "VAPID enabled state exposes publicKey, delivers decryptable RFC 8291 push, and cleans"
                    + " up 404/410 while surviving 500 (EC-01, EC-07)")
    void deliversEncryptedPushCleansUp404And410AndSurvives500() throws Exception {
        assertThat(pushSubscriptionService.getVapidPublicKey().enabled()).isTrue();
        assertThat(pushSubscriptionService.getVapidPublicKey().publicKey())
                .isEqualTo(vapidPublicKeyBase64Url);

        User user =
                userRepository.save(
                        new User(
                                "push@example.com",
                                "pushuser",
                                "Push User",
                                "hash",
                                "America/Bogota",
                                "12H",
                                FIXED_NOW,
                                FIXED_NOW));
        Group group = groupRepository.save(new Group(user, "Personal", true, FIXED_NOW, FIXED_NOW));

        // Date-only task due on 2026-11-18 (at 09:00 Bogota == FIXED_NOW)
        Task task =
                taskRepository.save(
                        new Task(
                                group,
                                null,
                                "Pagar internet",
                                null,
                                false,
                                null,
                                LocalDate.of(2026, 11, 18),
                                null,
                                FIXED_NOW,
                                FIXED_NOW));

        // Register 4 subscriptions: 201 OK, 410 Gone, 404 Not Found, 500 Internal Error
        PushSubscription subOk =
                pushSubscriptionRepository.save(
                        new PushSubscription(
                                user, baseUrl + "/push/201", clientP256dh, clientAuth, FIXED_NOW));
        PushSubscription sub410 =
                pushSubscriptionRepository.save(
                        new PushSubscription(
                                user, baseUrl + "/push/410", clientP256dh, clientAuth, FIXED_NOW));
        PushSubscription sub404 =
                pushSubscriptionRepository.save(
                        new PushSubscription(
                                user, baseUrl + "/push/404", clientP256dh, clientAuth, FIXED_NOW));
        PushSubscription sub500 =
                pushSubscriptionRepository.save(
                        new PushSubscription(
                                user, baseUrl + "/push/500", clientP256dh, clientAuth, FIXED_NOW));

        Optional<Instant> result =
                dueNotificationJobService.processDueTaskJob("task-due-" + task.getId());
        assertThat(result).isEmpty();

        // 1. In-app notification was persisted and NOT rolled back by 404/410/500
        List<Notification> notifications =
                notificationRepository.findFirstPage(
                        user.getId(), false, org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(notifications).hasSize(1);
        Notification savedNotification = notifications.getFirst();
        assertThat(savedNotification.getContent())
                .isEqualTo(
                        "La tarea \"Pagar internet\" del grupo \"Personal\" vence el 18/11/2026.");

        // 2. 410 and 404 subscriptions were auto-deleted; 201 and 500 subscriptions were preserved
        assertThat(pushSubscriptionRepository.findById(subOk.getId())).isPresent();
        assertThat(pushSubscriptionRepository.findById(sub500.getId())).isPresent();
        assertThat(pushSubscriptionRepository.findById(sub410.getId())).isEmpty();
        assertThat(pushSubscriptionRepository.findById(sub404.getId())).isEmpty();

        // 3. Decrypt actual HTTP payload received on /push/201 and verify verbatim body & VAPID JWT
        assertThat(LAST_ENCRYPTED_BODY.get()).isNotNull();
        WebPushPayload decrypted = decryptClientPayload(LAST_ENCRYPTED_BODY.get());
        assertThat(decrypted.notificationId()).isEqualTo(savedNotification.getId());
        assertThat(decrypted.eventType()).isEqualTo("TASK_DUE");
        assertThat(decrypted.title()).isEqualTo("Tarea por vencer");
        assertThat(decrypted.body()).isEqualTo(savedNotification.getContent());
        assertThat(decrypted.taskId()).isEqualTo(task.getId());
        assertThat(decrypted.groupId()).isEqualTo(group.getId());

        String authHeader = LAST_AUTH_HEADER.get();
        assertThat(authHeader).startsWith("vapid t=");
        String jwt = authHeader.substring("vapid t=".length(), authHeader.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        Signature verifier =
                Signature.getInstance("SHA256withPLAIN-ECDSA", WebPushCryptoUtils.bcProvider());
        verifier.initVerify(WebPushCryptoUtils.toEcPublicKey(vapidRawPublicBytes));
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(WebPushCryptoUtils.decodeBase64UrlOrStandard(parts[2])))
                .isTrue();
    }

    @Test
    @DisplayName(
            "Postponed task returns reschedule instant without persisting notification or calling"
                    + " push server (NTF-REQ-13)")
    void postponedTaskReschedulesWithoutPersistingOrCallingPush() {
        User user =
                userRepository.save(
                        new User(
                                "post@example.com",
                                "postuser",
                                "Post User",
                                "hash",
                                "UTC",
                                "24H",
                                FIXED_NOW,
                                FIXED_NOW));
        Group group = groupRepository.save(new Group(user, "Personal", true, FIXED_NOW, FIXED_NOW));
        Task futureTask =
                taskRepository.save(
                        new Task(
                                group,
                                null,
                                "Futura",
                                null,
                                false,
                                null,
                                LocalDate.of(2026, 11, 18),
                                LocalTime.of(18, 0),
                                FIXED_NOW,
                                FIXED_NOW));

        Optional<Instant> rescheduled =
                dueNotificationJobService.processDueTaskJob("task-due-" + futureTask.getId());
        assertThat(rescheduled).contains(Instant.parse("2026-11-18T18:00:00Z"));
        assertThat(notificationRepository.countByUserIdAndReadFalse(user.getId())).isZero();
        assertThat(LAST_ENCRYPTED_BODY.get()).isNull();
    }

    private WebPushPayload decryptClientPayload(byte[] encrypted) throws Exception {
        ByteBuffer buf = ByteBuffer.wrap(encrypted);
        byte[] salt = new byte[16];
        buf.get(salt);
        buf.getInt(); // rs
        int idlen = Byte.toUnsignedInt(buf.get());
        byte[] asPublicBytes = new byte[idlen];
        buf.get(asPublicBytes);
        byte[] ciphertextAndTag = new byte[buf.remaining()];
        buf.get(ciphertextAndTag);

        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(clientKeyPair.getPrivate());
        ka.doPhase(WebPushCryptoUtils.toEcPublicKey(asPublicBytes), true);
        byte[] ecdhSecret = ka.generateSecret();

        byte[] prkKey = WebPushEncryptionService.hmacSha256(clientAuthBytes, ecdhSecret);
        byte[] infoPrefix = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
        byte[] keyInfo =
                ByteBuffer.allocate(
                                infoPrefix.length + clientPublicBytes.length + asPublicBytes.length)
                        .put(infoPrefix)
                        .put(clientPublicBytes)
                        .put(asPublicBytes)
                        .array();
        byte[] ikm = WebPushEncryptionService.hkdfExpand(prkKey, keyInfo, 32);
        byte[] prk = WebPushEncryptionService.hmacSha256(salt, ikm);
        byte[] cek =
                WebPushEncryptionService.hkdfExpand(
                        prk,
                        "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII),
                        16);
        byte[] nonce =
                WebPushEncryptionService.hkdfExpand(
                        prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(cek, "AES"),
                new GCMParameterSpec(128, nonce));
        byte[] padded = cipher.doFinal(ciphertextAndTag);
        return objectMapper.readValue(
                Arrays.copyOf(padded, padded.length - 1), WebPushPayload.class);
    }
}
