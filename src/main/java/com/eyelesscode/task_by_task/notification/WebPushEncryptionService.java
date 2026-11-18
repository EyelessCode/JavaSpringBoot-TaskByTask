package com.eyelesscode.task_by_task.notification;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Pure Java 21 + BouncyCastle implementation of RFC 8291 ({@code aes128gcm}) payload encryption and
 * RFC 8292 ({@code ES256}) VAPID JWT authorization header generation.
 */
@Service
public class WebPushEncryptionService {

    public static final int RECORD_SIZE = 4096;
    public static final int HEADER_BYTES = 86;
    public static final int MAX_PLAINTEXT_BYTES = 3993;
    public static final long VAPID_EXPIRATION_SECONDS = 43_200L;

    private static final byte[] WEBPUSH_INFO_PREFIX =
            "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CEK_INFO =
            "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE_INFO =
            "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);
    private static final String JWT_HEADER_B64 =
            WebPushCryptoUtils.encodeBase64UrlNoPadding(
                    "{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));

    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public WebPushEncryptionService(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public byte[] serializeAndEncrypt(
            WebPushPayload payload, String p256dhEncoded, String authEncoded) {
        Objects.requireNonNull(payload, "payload must not be null");
        byte[] plaintextUtf8 = objectMapper.writeValueAsBytes(payload);
        return encrypt(plaintextUtf8, p256dhEncoded, authEncoded);
    }

    public byte[] encrypt(byte[] plaintextUtf8, String p256dhEncoded, String authEncoded) {
        Objects.requireNonNull(plaintextUtf8, "plaintextUtf8 must not be null");
        if (plaintextUtf8.length > MAX_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException(
                    "Web Push plaintext exceeds maximum single-record size of "
                            + MAX_PLAINTEXT_BYTES
                            + " bytes");
        }

        byte[] uaPublicBytes = WebPushCryptoUtils.decodeP256PublicKeyBytes(p256dhEncoded);
        ECPublicKey uaPublicKey = WebPushCryptoUtils.toEcPublicKey(uaPublicBytes);
        byte[] authSecret = WebPushCryptoUtils.decodeAuthSecret(authEncoded);

        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"), secureRandom);
            KeyPair asKeyPair = kpg.generateKeyPair();
            byte[] asPublicBytes =
                    WebPushCryptoUtils.encodeUncompressedPublicKey(
                            (ECPublicKey) asKeyPair.getPublic());

            byte[] salt = new byte[16];
            secureRandom.nextBytes(salt);

            KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH");
            keyAgreement.init(asKeyPair.getPrivate());
            keyAgreement.doPhase(uaPublicKey, true);
            byte[] ecdhSecret = keyAgreement.generateSecret();

            byte[] prkKey = hmacSha256(authSecret, ecdhSecret);
            byte[] keyInfo =
                    ByteBuffer.allocate(
                                    WEBPUSH_INFO_PREFIX.length
                                            + uaPublicBytes.length
                                            + asPublicBytes.length)
                            .put(WEBPUSH_INFO_PREFIX)
                            .put(uaPublicBytes)
                            .put(asPublicBytes)
                            .array();
            byte[] ikm = hkdfExpand(prkKey, keyInfo, 32);

            byte[] prk = hmacSha256(salt, ikm);
            byte[] cek = hkdfExpand(prk, CEK_INFO, 16);
            byte[] nonce = hkdfExpand(prk, NONCE_INFO, 12);

            byte[] paddedRecord = Arrays.copyOf(plaintextUtf8, plaintextUtf8.length + 1);
            paddedRecord[plaintextUtf8.length] = 0x02;

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(cek, "AES"),
                    new GCMParameterSpec(128, nonce));
            byte[] ciphertextAndTag = cipher.doFinal(paddedRecord);

            return ByteBuffer.allocate(HEADER_BYTES + ciphertextAndTag.length)
                    .put(salt)
                    .putInt(RECORD_SIZE)
                    .put((byte) asPublicBytes.length)
                    .put(asPublicBytes)
                    .put(ciphertextAndTag)
                    .array();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to encrypt Web Push payload", ex);
        }
    }

    public String createVapidAuthorizationHeader(
            String endpoint, VapidConfigurationState vapidState, Instant now) {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(vapidState, "vapidState must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (!vapidState.isActive() || vapidState.getPrivateKey() == null) {
            throw new IllegalStateException("VAPID configuration is not active");
        }

        String audience = extractOriginAudience(endpoint);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("aud", audience);
        claims.put("exp", now.getEpochSecond() + VAPID_EXPIRATION_SECONDS);
        claims.put("sub", vapidState.getSubject());

        String payloadB64 =
                WebPushCryptoUtils.encodeBase64UrlNoPadding(objectMapper.writeValueAsBytes(claims));
        String signingInput = JWT_HEADER_B64 + "." + payloadB64;

        try {
            Signature signature =
                    Signature.getInstance("SHA256withPLAIN-ECDSA", WebPushCryptoUtils.bcProvider());
            signature.initSign(vapidState.getPrivateKey(), secureRandom);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            byte[] sigBytes = signature.sign();
            if (sigBytes.length != 64) {
                throw new IllegalStateException(
                        "Expected 64-byte IEEE P1363 ECDSA signature, got " + sigBytes.length);
            }
            String jwt = signingInput + "." + WebPushCryptoUtils.encodeBase64UrlNoPadding(sigBytes);
            return "vapid t=" + jwt + ", k=" + vapidState.getPublicKey();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to sign VAPID JWT", ex);
        }
    }

    static byte[] hmacSha256(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        byte[] input = Arrays.copyOf(info, info.length + 1);
        input[info.length] = 0x01;
        byte[] okm = hmacSha256(prk, input);
        return Arrays.copyOf(okm, length);
    }

    private static String extractOriginAudience(String endpoint) {
        URI uri = URI.create(endpoint.trim());
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isBlank()) {
            throw new IllegalArgumentException("Invalid push endpoint URI: " + endpoint);
        }
        StringBuilder sb =
                new StringBuilder()
                        .append(scheme.toLowerCase(Locale.ROOT))
                        .append("://")
                        .append(host.toLowerCase(Locale.ROOT));
        if (uri.getPort() != -1) {
            sb.append(':').append(uri.getPort());
        }
        return sb.toString();
    }
}
