package com.eyelesscode.task_by_task.notification;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Provider;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;

/** Cryptographic helper utilities for Web Push (RFC 8291) and VAPID (RFC 8292) over P-256. */
public final class WebPushCryptoUtils {

    public static final int UNCOMPRESSED_PUBLIC_KEY_BYTES = 65;
    public static final int PRIVATE_KEY_SCALAR_BYTES = 32;
    public static final int AUTH_SECRET_BYTES = 16;

    private static final Provider BC_PROVIDER = new BouncyCastleProvider();
    private static final ECNamedCurveParameterSpec P256_BC_SPEC =
            ECNamedCurveTable.getParameterSpec("secp256r1");
    private static final ECParameterSpec P256_JCA_SPEC = initJcaParameterSpec();

    private WebPushCryptoUtils() {}

    public static Provider bcProvider() {
        return BC_PROVIDER;
    }

    public static ECParameterSpec ecParameterSpec() {
        return P256_JCA_SPEC;
    }

    public static byte[] decodeBase64UrlOrStandard(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("Base64 input must not be null");
        }
        String trimmed = encoded.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Base64 input must not be blank");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isWhitespace(trimmed.charAt(i))) {
                throw new IllegalArgumentException("Base64 input must not contain whitespace");
            }
        }

        boolean isStandard = trimmed.indexOf('+') >= 0 || trimmed.indexOf('/') >= 0;
        boolean isUrl = trimmed.indexOf('-') >= 0 || trimmed.indexOf('_') >= 0;
        if (isStandard && isUrl) {
            throw new IllegalArgumentException("Mixed Base64 and Base64URL alphabets");
        }

        String unpadded = stripTrailingPadding(trimmed);
        if (unpadded.indexOf('=') >= 0) {
            throw new IllegalArgumentException("Invalid padding character position");
        }
        if (unpadded.length() % 4 == 1) {
            throw new IllegalArgumentException("Invalid Base64 length");
        }

        if (isStandard) {
            String padded = padBase64(unpadded);
            return Base64.getDecoder().decode(padded);
        }
        return Base64.getUrlDecoder().decode(unpadded);
    }

    public static String encodeBase64UrlNoPadding(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] decodeP256PublicKeyBytes(String encoded) {
        byte[] bytes = decodeBase64UrlOrStandard(encoded);
        if (bytes.length != UNCOMPRESSED_PUBLIC_KEY_BYTES || bytes[0] != 0x04) {
            throw new IllegalArgumentException(
                    "P-256 public key must be 65 uncompressed bytes starting with 0x04");
        }
        org.bouncycastle.math.ec.ECPoint point;
        try {
            point = P256_BC_SPEC.getCurve().decodePoint(bytes);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid secp256r1 curve point", ex);
        }
        if (point == null || point.isInfinity() || !point.isValid()) {
            throw new IllegalArgumentException("Public key point is not valid on secp256r1");
        }
        return bytes;
    }

    public static ECPublicKey decodeP256PublicKey(String encoded) {
        return toEcPublicKey(decodeP256PublicKeyBytes(encoded));
    }

    public static ECPublicKey toEcPublicKey(byte[] uncompressed65Bytes) {
        if (uncompressed65Bytes == null
                || uncompressed65Bytes.length != UNCOMPRESSED_PUBLIC_KEY_BYTES
                || uncompressed65Bytes[0] != 0x04) {
            throw new IllegalArgumentException("Invalid uncompressed P-256 public key bytes");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed65Bytes, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed65Bytes, 33, 65));
        try {
            KeyFactory kf = KeyFactory.getInstance("EC");
            return (ECPublicKey)
                    kf.generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256_JCA_SPEC));
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("Failed to construct JCA ECPublicKey", ex);
        }
    }

    public static ECPrivateKey decodeP256PrivateKey(String encoded) {
        byte[] bytes = decodeBase64UrlOrStandard(encoded);
        if (bytes.length != PRIVATE_KEY_SCALAR_BYTES) {
            throw new IllegalArgumentException("P-256 private key scalar must be 32 bytes");
        }
        BigInteger s = new BigInteger(1, bytes);
        if (s.compareTo(BigInteger.ONE) < 0 || s.compareTo(P256_BC_SPEC.getN()) >= 0) {
            throw new IllegalArgumentException("P-256 private key scalar out of range [1, n-1]");
        }
        try {
            KeyFactory kf = KeyFactory.getInstance("EC");
            return (ECPrivateKey) kf.generatePrivate(new ECPrivateKeySpec(s, P256_JCA_SPEC));
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("Failed to construct JCA ECPrivateKey", ex);
        }
    }

    public static boolean isKeyPairMatching(ECPrivateKey privateKey, byte[] publicKeyBytes) {
        if (privateKey == null
                || publicKeyBytes == null
                || publicKeyBytes.length != UNCOMPRESSED_PUBLIC_KEY_BYTES) {
            return false;
        }
        try {
            org.bouncycastle.math.ec.ECPoint q =
                    P256_BC_SPEC.getG().multiply(privateKey.getS()).normalize();
            if (q == null || q.isInfinity() || !q.isValid()) {
                return false;
            }
            return MessageDigest.isEqual(q.getEncoded(false), publicKeyBytes);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public static byte[] decodeAuthSecret(String encoded) {
        byte[] bytes = decodeBase64UrlOrStandard(encoded);
        if (bytes.length != AUTH_SECRET_BYTES) {
            throw new IllegalArgumentException("Web Push auth secret must decode to 16 bytes");
        }
        return bytes;
    }

    public static void validateAuthSecret(String encoded) {
        decodeAuthSecret(encoded);
    }

    public static byte[] encodeUncompressedPublicKey(ECPublicKey publicKey) {
        ECPoint w = publicKey.getW();
        byte[] out = new byte[UNCOMPRESSED_PUBLIC_KEY_BYTES];
        out[0] = 0x04;
        byte[] xBytes = toFixedBytes(w.getAffineX(), 32);
        byte[] yBytes = toFixedBytes(w.getAffineY(), 32);
        System.arraycopy(xBytes, 0, out, 1, 32);
        System.arraycopy(yBytes, 0, out, 33, 32);
        return out;
    }

    public static byte[] encodePrivateKeyScalar(ECPrivateKey privateKey) {
        return toFixedBytes(privateKey.getS(), PRIVATE_KEY_SCALAR_BYTES);
    }

    public static byte[] toFixedBytes(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        if (raw.length == length) {
            return raw;
        }
        if (raw.length == length + 1 && raw[0] == 0) {
            return Arrays.copyOfRange(raw, 1, raw.length);
        }
        if (raw.length < length) {
            byte[] padded = new byte[length];
            System.arraycopy(raw, 0, padded, length - raw.length, raw.length);
            return padded;
        }
        throw new IllegalArgumentException("BigInteger exceeds target byte length " + length);
    }

    private static String stripTrailingPadding(String s) {
        int end = s.length();
        int padCount = 0;
        while (end > 0 && s.charAt(end - 1) == '=') {
            end--;
            padCount++;
        }
        if (padCount > 2) {
            throw new IllegalArgumentException("Too many Base64 padding characters");
        }
        return s.substring(0, end);
    }

    private static String padBase64(String unpadded) {
        int rem = unpadded.length() % 4;
        if (rem == 0) {
            return unpadded;
        }
        if (rem == 2) {
            return unpadded + "==";
        }
        return unpadded + "=";
    }

    private static ECParameterSpec initJcaParameterSpec() {
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            return params.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to initialize secp256r1 ECParameterSpec", ex);
        }
    }
}
