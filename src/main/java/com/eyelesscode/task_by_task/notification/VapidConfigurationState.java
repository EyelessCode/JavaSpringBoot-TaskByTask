package com.eyelesscode.task_by_task.notification;

import java.net.URI;
import java.security.interfaces.ECPrivateKey;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validated runtime state for VAPID Web Push configuration with deterministic safe degradation when
 * disabled, partially configured, or cryptographically invalid.
 */
@Component
public class VapidConfigurationState {

    private static final Logger log = LoggerFactory.getLogger(VapidConfigurationState.class);

    private final boolean active;
    private final String publicKey;
    private final byte[] rawPublicKeyBytes;
    private final ECPrivateKey privateKey;
    private final String subject;

    public VapidConfigurationState(VapidProperties properties) {
        if (properties == null || !properties.enabled()) {
            log.info(
                    "Web Push VAPID deshabilitado por configuración (app.web-push.enabled=false)."
                            + " Las notificaciones in-app seguirán activas.");
            this.active = false;
            this.publicKey = null;
            this.rawPublicKeyBytes = null;
            this.privateKey = null;
            this.subject = null;
            return;
        }

        VapidProperties.VapidKeys vapid = properties.vapid();
        String rawPub = vapid != null && vapid.publicKey() != null ? vapid.publicKey().trim() : "";
        String rawPriv =
                vapid != null && vapid.privateKey() != null ? vapid.privateKey().trim() : "";
        String rawSub = vapid != null && vapid.subject() != null ? vapid.subject().trim() : "";

        if (rawPub.isEmpty() || rawPriv.isEmpty() || rawSub.isEmpty()) {
            log.warn(
                    "Web Push VAPID habilitado pero la configuración está incompleta"
                            + " (publicKeyPresent={}, privateKeyPresent={}, subjectPresent={}). Se"
                            + " degrada de forma segura a estado inactivo.",
                    !rawPub.isEmpty(),
                    !rawPriv.isEmpty(),
                    !rawSub.isEmpty());
            this.active = false;
            this.publicKey = null;
            this.rawPublicKeyBytes = null;
            this.privateKey = null;
            this.subject = null;
            return;
        }

        if (!isValidSubjectUri(rawSub)) {
            log.error(
                    "El subject VAPID '{}' no es un URI 'mailto:' o 'https:' válido. Web Push se"
                            + " desactiva de forma segura.",
                    rawSub);
            this.active = false;
            this.publicKey = null;
            this.rawPublicKeyBytes = null;
            this.privateKey = null;
            this.subject = null;
            return;
        }

        byte[] pubBytes;
        ECPrivateKey privKey;
        try {
            pubBytes = WebPushCryptoUtils.decodeP256PublicKeyBytes(rawPub);
            privKey = WebPushCryptoUtils.decodeP256PrivateKey(rawPriv);
        } catch (IllegalArgumentException ex) {
            log.error(
                    "Las claves VAPID configuradas no cumplen el formato EC P-256 (secp256r1) ({})."
                            + " Web Push se desactiva de forma segura.",
                    ex.getMessage());
            this.active = false;
            this.publicKey = null;
            this.rawPublicKeyBytes = null;
            this.privateKey = null;
            this.subject = null;
            return;
        }

        if (!WebPushCryptoUtils.isKeyPairMatching(privKey, pubBytes)) {
            log.error(
                    "La clave privada VAPID no corresponde matemáticamente a la clave pública"
                            + " P-256 configurada (d * G != Q). Web Push se desactiva de forma"
                            + " segura.");
            this.active = false;
            this.publicKey = null;
            this.rawPublicKeyBytes = null;
            this.privateKey = null;
            this.subject = null;
            return;
        }

        this.active = true;
        this.rawPublicKeyBytes = Arrays.copyOf(pubBytes, pubBytes.length);
        this.publicKey = WebPushCryptoUtils.encodeBase64UrlNoPadding(pubBytes);
        this.privateKey = privKey;
        this.subject = rawSub;
        log.info("Configuración Web Push VAPID validada y activa (subject={}).", rawSub);
    }

    public boolean isActive() {
        return active;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public byte[] getRawPublicKeyBytes() {
        return rawPublicKeyBytes != null
                ? Arrays.copyOf(rawPublicKeyBytes, rawPublicKeyBytes.length)
                : null;
    }

    public ECPrivateKey getPrivateKey() {
        return privateKey;
    }

    public String getSubject() {
        return subject;
    }

    private static boolean isValidSubjectUri(String subject) {
        try {
            URI uri = URI.create(subject);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return false;
            }
            if ("mailto".equalsIgnoreCase(scheme)) {
                String ssp = uri.getSchemeSpecificPart();
                return ssp != null && !ssp.isBlank() && ssp.contains("@");
            }
            if ("https".equalsIgnoreCase(scheme)) {
                String host = uri.getHost();
                return host != null && !host.isBlank();
            }
            return false;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
