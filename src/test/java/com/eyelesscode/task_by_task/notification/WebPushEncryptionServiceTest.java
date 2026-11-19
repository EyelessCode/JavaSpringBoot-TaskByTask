package com.eyelesscode.task_by_task.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class WebPushEncryptionServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
    private static final WebPushEncryptionService ENCRYPTION_SERVICE =
            new WebPushEncryptionService(OBJECT_MAPPER);

    private static KeyPair uaKeyPair;
    private static byte[] uaPublicBytes;
    private static String uaP256dhBase64Url;
    private static byte[] uaAuthBytes;
    private static String uaAuthBase64Url;
    private static VapidConfigurationState activeVapidState;

    @BeforeAll
    static void setUpCrypto() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));

        uaKeyPair = kpg.generateKeyPair();
        uaPublicBytes =
                WebPushCryptoUtils.encodeUncompressedPublicKey((ECPublicKey) uaKeyPair.getPublic());
        uaP256dhBase64Url = WebPushCryptoUtils.encodeBase64UrlNoPadding(uaPublicBytes);

        uaAuthBytes = new byte[16];
        new SecureRandom().nextBytes(uaAuthBytes);
        uaAuthBase64Url = WebPushCryptoUtils.encodeBase64UrlNoPadding(uaAuthBytes);

        KeyPair vapidKeyPair = kpg.generateKeyPair();
        String vapidPub =
                WebPushCryptoUtils.encodeBase64UrlNoPadding(
                        WebPushCryptoUtils.encodeUncompressedPublicKey(
                                (ECPublicKey) vapidKeyPair.getPublic()));
        String vapidPriv =
                WebPushCryptoUtils.encodeBase64UrlNoPadding(
                        WebPushCryptoUtils.encodePrivateKeyScalar(
                                (ECPrivateKey) vapidKeyPair.getPrivate()));

        activeVapidState =
                new VapidConfigurationState(
                        new VapidProperties(
                                true,
                                new VapidProperties.VapidKeys(
                                        vapidPub,
                                        vapidPriv,
                                        "mailto:notificaciones@taskbytask.app")));
    }

    @Test
    @DisplayName(
            "RFC 8291 aes128gcm encryption round-trips cleanly with full client-side"
                    + " ECDH+HKDF+AES-GCM decryption")
    void encryptProducesValidRfc8291Aes128GcmMessageDecryptableByClient() throws Exception {
        Instant createdAt = Instant.parse("2026-11-18T14:30:00Z");
        WebPushPayload original =
                new WebPushPayload(
                        42L,
                        "TASK_DUE",
                        "Tarea por vencer",
                        "La tarea \"Preparar café\" del grupo \"Personal\" vence el 18/11/2026 a"
                                + " las 09:00.",
                        101L,
                        7L,
                        createdAt);

        byte[] encrypted =
                ENCRYPTION_SERVICE.serializeAndEncrypt(
                        original, uaP256dhBase64Url, uaAuthBase64Url);

        assertThat(encrypted.length).isGreaterThan(WebPushEncryptionService.HEADER_BYTES + 16);

        ByteBuffer buf = ByteBuffer.wrap(encrypted);
        byte[] salt = new byte[16];
        buf.get(salt);
        int rs = buf.getInt();
        int idlen = Byte.toUnsignedInt(buf.get());
        assertThat(rs).isEqualTo(4096);
        assertThat(idlen).isEqualTo(65);

        byte[] asPublicBytes = new byte[idlen];
        buf.get(asPublicBytes);
        byte[] ciphertextAndTag = new byte[buf.remaining()];
        buf.get(ciphertextAndTag);

        // Client-side ECDH + HKDF-SHA256 + AES-128-GCM decryption
        ECPublicKey asPublicKey = WebPushCryptoUtils.toEcPublicKey(asPublicBytes);
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(uaKeyPair.getPrivate());
        ka.doPhase(asPublicKey, true);
        byte[] ecdhSecret = ka.generateSecret();

        byte[] prkKey = WebPushEncryptionService.hmacSha256(uaAuthBytes, ecdhSecret);
        byte[] infoPrefix = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
        byte[] keyInfo =
                ByteBuffer.allocate(infoPrefix.length + uaPublicBytes.length + asPublicBytes.length)
                        .put(infoPrefix)
                        .put(uaPublicBytes)
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
        byte[] paddedPlaintext = cipher.doFinal(ciphertextAndTag);

        assertThat(paddedPlaintext[paddedPlaintext.length - 1]).isEqualTo((byte) 0x02);
        byte[] jsonBytes = Arrays.copyOf(paddedPlaintext, paddedPlaintext.length - 1);

        WebPushPayload decrypted = OBJECT_MAPPER.readValue(jsonBytes, WebPushPayload.class);
        assertThat(decrypted).isEqualTo(original);
    }

    @Test
    @DisplayName(
            "RFC 8292 VAPID Authorization header embeds valid ES256 64-byte signature and origin"
                    + " claims")
    void createVapidAuthorizationHeaderSignsValidEs256Jwt() throws Exception {
        Instant now = Instant.parse("2026-11-18T12:00:00Z");
        String endpoint = "https://fcm.googleapis.com:8443/fcm/send/sub-token-123";

        String headerValue =
                ENCRYPTION_SERVICE.createVapidAuthorizationHeader(endpoint, activeVapidState, now);

        assertThat(headerValue).startsWith("vapid t=");
        assertThat(headerValue).endsWith(", k=" + activeVapidState.getPublicKey());

        String jwt = headerValue.substring("vapid t=".length(), headerValue.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);

        JsonNode jwtHeader =
                OBJECT_MAPPER.readTree(WebPushCryptoUtils.decodeBase64UrlOrStandard(parts[0]));
        assertThat(jwtHeader.get("typ").asString()).isEqualTo("JWT");
        assertThat(jwtHeader.get("alg").asString()).isEqualTo("ES256");

        JsonNode jwtPayload =
                OBJECT_MAPPER.readTree(WebPushCryptoUtils.decodeBase64UrlOrStandard(parts[1]));
        assertThat(jwtPayload.get("aud").asString()).isEqualTo("https://fcm.googleapis.com:8443");
        assertThat(jwtPayload.get("exp").asLong()).isEqualTo(now.getEpochSecond() + 43_200L);
        assertThat(jwtPayload.get("sub").asString())
                .isEqualTo("mailto:notificaciones@taskbytask.app");

        byte[] sigBytes = WebPushCryptoUtils.decodeBase64UrlOrStandard(parts[2]);
        assertThat(sigBytes).hasSize(64);

        Signature verifier =
                Signature.getInstance("SHA256withPLAIN-ECDSA", WebPushCryptoUtils.bcProvider());
        verifier.initVerify(
                WebPushCryptoUtils.toEcPublicKey(activeVapidState.getRawPublicKeyBytes()));
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(sigBytes)).isTrue();
    }

    @Test
    @DisplayName("Rejects oversized plaintext exceeding single RFC 8188 record")
    void rejectsOversizedPlaintext() {
        byte[] tooLarge = new byte[WebPushEncryptionService.MAX_PLAINTEXT_BYTES + 1];
        assertThatThrownBy(
                        () ->
                                ENCRYPTION_SERVICE.encrypt(
                                        tooLarge, uaP256dhBase64Url, uaAuthBase64Url))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
