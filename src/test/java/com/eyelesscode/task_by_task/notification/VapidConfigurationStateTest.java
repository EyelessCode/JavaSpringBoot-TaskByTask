package com.eyelesscode.task_by_task.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VapidConfigurationStateTest {

    private static String validPubBase64Url;
    private static String validPrivBase64Url;
    private static String validPubStandardBase64;
    private static String validPrivStandardBase64;
    private static String secondPubBase64Url;

    @BeforeAll
    static void setUpKeys() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));

        KeyPair kp1 = kpg.generateKeyPair();
        byte[] pub1 = WebPushCryptoUtils.encodeUncompressedPublicKey((ECPublicKey) kp1.getPublic());
        byte[] priv1 = WebPushCryptoUtils.encodePrivateKeyScalar((ECPrivateKey) kp1.getPrivate());

        validPubBase64Url = WebPushCryptoUtils.encodeBase64UrlNoPadding(pub1);
        validPrivBase64Url = WebPushCryptoUtils.encodeBase64UrlNoPadding(priv1);
        validPubStandardBase64 = Base64.getEncoder().encodeToString(pub1);
        validPrivStandardBase64 = Base64.getEncoder().encodeToString(priv1);

        KeyPair kp2 = kpg.generateKeyPair();
        byte[] pub2 = WebPushCryptoUtils.encodeUncompressedPublicKey((ECPublicKey) kp2.getPublic());
        secondPubBase64Url = WebPushCryptoUtils.encodeBase64UrlNoPadding(pub2);
    }

    @Test
    @DisplayName(
            "Valid P-256 keypair and mailto/https subject activates VAPID with canonical Base64URL"
                    + " publicKey")
    void activatesWhenValidBase64UrlOrStandardBase64Configured() {
        VapidConfigurationState state1 =
                new VapidConfigurationState(
                        new VapidProperties(
                                true,
                                new VapidProperties.VapidKeys(
                                        "  " + validPubStandardBase64 + " ",
                                        " " + validPrivStandardBase64 + "  ",
                                        " mailto:notificaciones@taskbytask.app ")));

        assertThat(state1.isActive()).isTrue();
        assertThat(state1.getPublicKey()).isEqualTo(validPubBase64Url);
        assertThat(state1.getRawPublicKeyBytes()).hasSize(65);
        assertThat(state1.getPrivateKey()).isNotNull();
        assertThat(state1.getSubject()).isEqualTo("mailto:notificaciones@taskbytask.app");

        VapidConfigurationState state2 =
                new VapidConfigurationState(
                        new VapidProperties(
                                true,
                                new VapidProperties.VapidKeys(
                                        validPubBase64Url,
                                        validPrivBase64Url,
                                        "https://taskbytask.app")));
        assertThat(state2.isActive()).isTrue();
        assertThat(state2.getPublicKey()).isEqualTo(validPubBase64Url);
    }

    @Test
    @DisplayName(
            "Safe degradation to inactive with publicKey=null when disabled or partially configured"
                    + " (EC-07)")
    void degradesSafelyWhenDisabledOrPartial() {
        VapidConfigurationState disabled =
                new VapidConfigurationState(
                        new VapidProperties(
                                false,
                                new VapidProperties.VapidKeys(
                                        validPubBase64Url,
                                        validPrivBase64Url,
                                        "mailto:test@example.com")));
        assertThat(disabled.isActive()).isFalse();
        assertThat(disabled.getPublicKey()).isNull();
        assertThat(disabled.getPrivateKey()).isNull();

        VapidConfigurationState missingPriv =
                new VapidConfigurationState(
                        new VapidProperties(
                                true,
                                new VapidProperties.VapidKeys(
                                        validPubBase64Url, "   ", "mailto:test@example.com")));
        assertThat(missingPriv.isActive()).isFalse();
        assertThat(missingPriv.getPublicKey()).isNull();

        VapidConfigurationState missingSubject =
                new VapidConfigurationState(
                        new VapidProperties(
                                true,
                                new VapidProperties.VapidKeys(
                                        validPubBase64Url, validPrivBase64Url, "")));
        assertThat(missingSubject.isActive()).isFalse();
        assertThat(missingSubject.getPublicKey()).isNull();
    }

    @Test
    @DisplayName(
            "Safe degradation when subject URI is invalid, keys are malformed, or keypair is"
                    + " mismatched (FND-REQ-35)")
    void degradesSafelyOnInvalidSubjectMalformedKeysOrMismatchedPair() {
        // http:// subject (not https: or mailto:)
        VapidConfigurationState badSubject =
                new VapidConfigurationState(
                        new VapidProperties(
                                true,
                                new VapidProperties.VapidKeys(
                                        validPubBase64Url,
                                        validPrivBase64Url,
                                        "http://insecure.example.com")));
        assertThat(badSubject.isActive()).isFalse();
        assertThat(badSubject.getPublicKey()).isNull();

        // Malformed public key (wrong length)
        VapidConfigurationState badPub =
                new VapidConfigurationState(
                        new VapidProperties(
                                true,
                                new VapidProperties.VapidKeys(
                                        WebPushCryptoUtils.encodeBase64UrlNoPadding(new byte[32]),
                                        validPrivBase64Url,
                                        "mailto:test@example.com")));
        assertThat(badPub.isActive()).isFalse();
        assertThat(badPub.getPublicKey()).isNull();

        // Point not on secp256r1 curve (0x04 followed by 64 zero bytes)
        byte[] offCurve = new byte[65];
        offCurve[0] = 0x04;
        VapidConfigurationState offCurveState =
                new VapidConfigurationState(
                        new VapidProperties(
                                true,
                                new VapidProperties.VapidKeys(
                                        WebPushCryptoUtils.encodeBase64UrlNoPadding(offCurve),
                                        validPrivBase64Url,
                                        "mailto:test@example.com")));
        assertThat(offCurveState.isActive()).isFalse();
        assertThat(offCurveState.getPublicKey()).isNull();

        // Mismatched P-256 keypair (d1 != Q2)
        VapidConfigurationState mismatched =
                new VapidConfigurationState(
                        new VapidProperties(
                                true,
                                new VapidProperties.VapidKeys(
                                        secondPubBase64Url,
                                        validPrivBase64Url,
                                        "mailto:test@example.com")));
        assertThat(mismatched.isActive()).isFalse();
        assertThat(mismatched.getPublicKey()).isNull();
    }

    @Test
    @DisplayName(
            "WebPushCryptoUtils validates 16-byte auth secret and rejects invalid"
                    + " lengths/whitespace")
    void cryptoUtilsValidatesAuthSecretAndRejectsInvalidInputs() {
        String validAuth = WebPushCryptoUtils.encodeBase64UrlNoPadding(new byte[16]);
        assertThat(WebPushCryptoUtils.decodeAuthSecret(validAuth)).hasSize(16);

        String shortAuth = WebPushCryptoUtils.encodeBase64UrlNoPadding(new byte[15]);
        assertThatThrownBy(() -> WebPushCryptoUtils.validateAuthSecret(shortAuth))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> WebPushCryptoUtils.decodeBase64UrlOrStandard("abc def"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
