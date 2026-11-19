package com.eyelesscode.task_by_task.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.eyelesscode.task_by_task.notification.dto.RegisterPushSubscriptionRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class RegisterPushSubscriptionRequestValidationTest {

    private static Validator validator;
    private static String validP256dh;
    private static String validAuth;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @BeforeAll
    static void setUp() throws Exception {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();
        validP256dh =
                WebPushCryptoUtils.encodeBase64UrlNoPadding(
                        WebPushCryptoUtils.encodeUncompressedPublicKey(
                                (ECPublicKey) kp.getPublic()));
        validAuth = WebPushCryptoUtils.encodeBase64UrlNoPadding(new byte[16]);
    }

    @Test
    @DisplayName(
            "Valid W3C payload trims fields and ignores expirationTime and server-managed fields")
    void validPayloadTrimsAndIgnoresExtraFields() {
        String json =
                String.format(
                        """
                        {
                          "id": 999,
                          "userId": 888,
                          "createdAt": "2020-01-01T00:00:00Z",
                          "expirationTime": null,
                          "endpoint": "  https://fcm.googleapis.com/fcm/send/abc  ",
                          "keys": {
                            "p256dh": " %s ",
                            "auth": " %s ",
                            "extra": "ignored"
                          }
                        }
                        """,
                        validP256dh, validAuth);

        RegisterPushSubscriptionRequest req =
                objectMapper.readValue(json, RegisterPushSubscriptionRequest.class);

        assertThat(req.endpoint()).isEqualTo("https://fcm.googleapis.com/fcm/send/abc");
        assertThat(req.keys().p256dh()).isEqualTo(validP256dh);
        assertThat(req.keys().auth()).isEqualTo(validAuth);
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName(
            "Blank fields produce a single @NotBlank violation without duplicate custom constraint"
                    + " errors")
    void blankFieldsProduceSingleViolationPerField() {
        RegisterPushSubscriptionRequest req =
                new RegisterPushSubscriptionRequest(
                        "   ", new RegisterPushSubscriptionRequest.PushSubscriptionKeys(" ", ""));

        Set<ConstraintViolation<RegisterPushSubscriptionRequest>> violations =
                validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("endpoint", "keys.p256dh", "keys.auth");
    }

    @Test
    @DisplayName(
            "Rejects non-http/https endpoints, off-curve p256dh keys, and non-16-byte auth secrets")
    void rejectsInvalidUrlCurvePointAndAuthSecret() {
        byte[] offCurve = new byte[65];
        offCurve[0] = 0x04;
        String invalidP256dh = WebPushCryptoUtils.encodeBase64UrlNoPadding(offCurve);
        String invalidAuth = WebPushCryptoUtils.encodeBase64UrlNoPadding(new byte[12]);

        RegisterPushSubscriptionRequest req =
                new RegisterPushSubscriptionRequest(
                        "ftp://push.example.com/sub",
                        new RegisterPushSubscriptionRequest.PushSubscriptionKeys(
                                invalidP256dh, invalidAuth));

        Set<ConstraintViolation<RegisterPushSubscriptionRequest>> violations =
                validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("endpoint", "keys.p256dh", "keys.auth");
    }
}
