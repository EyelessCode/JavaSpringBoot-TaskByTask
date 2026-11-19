package com.eyelesscode.task_by_task.shared.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Pure IPv4/IPv6 CIDR matcher for trusted reverse proxies ({@code app.security.trusted-proxies}).
 * Never performs DNS lookups at runtime.
 */
@Component
public class TrustedProxyResolver {

    private final List<IpRange> trustedRanges;

    @Autowired
    public TrustedProxyResolver(SecurityProperties securityProperties) {
        this(
                Objects.requireNonNull(securityProperties, "securityProperties must not be null")
                        .trustedProxies());
    }

    public TrustedProxyResolver(List<String> cidrs) {
        if (cidrs == null || cidrs.isEmpty()) {
            this.trustedRanges = List.of();
        } else {
            this.trustedRanges = cidrs.stream().map(TrustedProxyResolver::parseRange).toList();
        }
    }

    public static TrustedProxyResolver withDefaultTrustedProxies() {
        return new TrustedProxyResolver(SecurityProperties.DEFAULT_TRUSTED_PROXIES);
    }

    /** Returns {@code true} only if {@code remoteAddr} is a valid IP literal in a trusted CIDR. */
    public boolean isTrustedProxy(String remoteAddr) {
        byte[] address = parseIpLiteral(remoteAddr);
        if (address == null) {
            return false;
        }
        for (IpRange range : trustedRanges) {
            if (range.matches(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the real client IP. Forwarded headers are honored only when {@code remoteAddr}
     * belongs to a configured trusted proxy; otherwise they are ignored to prevent IP spoofing.
     */
    public String extractClientIp(
            String remoteAddr, String xForwardedForHeader, String xRealIpHeader) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return "UNKNOWN";
        }
        String trimmedRemote = remoteAddr.trim();
        if (!isTrustedProxy(trimmedRemote)) {
            return trimmedRemote;
        }
        if (xForwardedForHeader != null && !xForwardedForHeader.isBlank()) {
            for (String candidate : xForwardedForHeader.split(",")) {
                String trimmed = candidate.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        if (xRealIpHeader != null && !xRealIpHeader.isBlank()) {
            return xRealIpHeader.trim();
        }
        return trimmedRemote;
    }

    private static IpRange parseRange(String rawCidr) {
        if (rawCidr == null || rawCidr.isBlank()) {
            throw new IllegalStateException("app.security.trusted-proxies contiene un CIDR vacío");
        }
        String trimmed = rawCidr.trim();
        String[] parts = trimmed.split("/", -1);
        if (parts.length > 2) {
            throw new IllegalStateException(
                    "CIDR inválido en app.security.trusted-proxies: " + rawCidr);
        }

        byte[] address = parseIpLiteral(parts[0]);
        if (address == null) {
            throw new IllegalStateException(
                    "Dirección IP inválida en app.security.trusted-proxies: " + rawCidr);
        }

        int maxPrefix = address.length * 8;
        int prefixLength = maxPrefix;
        if (parts.length == 2) {
            try {
                prefixLength = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException ex) {
                throw new IllegalStateException(
                        "Prefijo CIDR inválido en app.security.trusted-proxies: " + rawCidr, ex);
            }
            if (prefixLength < 0 || prefixLength > maxPrefix) {
                throw new IllegalStateException(
                        "Prefijo CIDR fuera de rango en app.security.trusted-proxies: " + rawCidr);
            }
        }

        return new IpRange(address, prefixLength);
    }

    private static byte[] parseIpLiteral(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String candidate = raw.trim();
        if (candidate.startsWith("[") && candidate.endsWith("]") && candidate.length() > 2) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        int zoneIndex = candidate.indexOf('%');
        if (zoneIndex > 0) {
            candidate = candidate.substring(0, zoneIndex);
        }

        if (candidate.indexOf(':') >= 0) {
            return parseIpv6Literal(candidate);
        }
        return parseIpv4Literal(candidate);
    }

    private static byte[] parseIpv4Literal(String candidate) {
        String[] octets = candidate.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            String octet = octets[i];
            if (octet.isEmpty() || octet.length() > 3) {
                return null;
            }
            int value = 0;
            for (int j = 0; j < octet.length(); j++) {
                char c = octet.charAt(j);
                if (c < '0' || c > '9') {
                    return null;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return null;
            }
            bytes[i] = (byte) value;
        }
        return bytes;
    }

    private static byte[] parseIpv6Literal(String candidate) {
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean valid =
                    (c >= '0' && c <= '9')
                            || (c >= 'a' && c <= 'f')
                            || (c >= 'A' && c <= 'F')
                            || c == ':'
                            || c == '.';
            if (!valid) {
                return null;
            }
        }
        try {
            byte[] bytes = InetAddress.getByName(candidate).getAddress();
            return normalizeIpv4Mapped(bytes);
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    private static byte[] normalizeIpv4Mapped(byte[] bytes) {
        if (bytes.length != 16) {
            return bytes;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return bytes;
            }
        }
        if (bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF) {
            return new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
        }
        return bytes;
    }

    private record IpRange(byte[] networkAddress, int prefixLength) {

        boolean matches(byte[] candidate) {
            if (networkAddress.length != candidate.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (networkAddress[i] != candidate[i]) {
                    return false;
                }
            }
            if (remainingBits > 0) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                return (networkAddress[fullBytes] & mask) == (candidate[fullBytes] & mask);
            }
            return true;
        }
    }
}
