package com.eyelesscode.task_by_task.shared.security;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Externalized security configuration ({@code app.security.*}) for CORS, cookie policy and trusted
 * reverse proxies. Invalid combinations fail fast at startup (HRD-REQ-02, HRD-REQ-05).
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        @DefaultValue Cors cors, @DefaultValue Cookie cookie, List<String> trustedProxies) {

    public static final List<String> DEFAULT_TRUSTED_PROXIES =
            List.of(
                    "127.0.0.1/32",
                    "::1/128",
                    "10.0.0.0/8",
                    "172.16.0.0/12",
                    "192.168.0.0/16",
                    "fc00::/7");

    public SecurityProperties {
        cors = cors != null ? cors : new Cors(List.of(), Cors.DEFAULT_MAX_AGE_SECONDS);
        cookie = cookie != null ? cookie : new Cookie(true, Cookie.DEFAULT_SAME_SITE, null);
        trustedProxies =
                trustedProxies == null ? DEFAULT_TRUSTED_PROXIES : cleanList(trustedProxies);
    }

    /** CORS policy. Wildcards are forbidden because credentials are always allowed. */
    public record Cors(List<String> allowedOrigins, @DefaultValue("3600") long maxAgeSeconds) {

        public static final long DEFAULT_MAX_AGE_SECONDS = 3600L;

        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : cleanList(allowedOrigins);
            if (allowedOrigins.stream().anyMatch(origin -> origin.contains("*"))) {
                throw new IllegalStateException(
                        "app.security.cors.allowed-origins no puede contener comodines '*' cuando"
                                + " allowCredentials=true");
            }
            if (maxAgeSeconds < 0) {
                throw new IllegalStateException(
                        "app.security.cors.max-age-seconds no puede ser negativo");
            }
        }
    }

    /** Unified cookie policy shared by {@code SESSION} and {@code XSRF-TOKEN}. */
    public record Cookie(
            @DefaultValue("true") boolean secure,
            @DefaultValue(DEFAULT_SAME_SITE) String sameSite,
            String domain) {

        public static final String DEFAULT_SAME_SITE = "Lax";

        public Cookie {
            sameSite = normalizeSameSite(sameSite);
            if ("None".equals(sameSite) && !secure) {
                throw new IllegalStateException(
                        "app.security.cookie: SameSite=None exige app.security.cookie.secure=true");
            }
            domain = domain == null || domain.isBlank() ? null : domain.trim();
        }

        private static String normalizeSameSite(String value) {
            if (value == null || value.isBlank()) {
                return DEFAULT_SAME_SITE;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "lax" -> "Lax";
                case "strict" -> "Strict";
                case "none" -> "None";
                default ->
                        throw new IllegalStateException(
                                "app.security.cookie.same-site inválido: '"
                                        + value
                                        + "'. Valores permitidos para SameSite: Lax, Strict, None");
            };
        }
    }

    private static List<String> cleanList(List<String> values) {
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
