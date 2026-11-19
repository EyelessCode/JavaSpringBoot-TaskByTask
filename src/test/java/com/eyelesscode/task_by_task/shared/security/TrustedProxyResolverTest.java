package com.eyelesscode.task_by_task.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TrustedProxyResolverTest {

    private final TrustedProxyResolver defaultResolver =
            TrustedProxyResolver.withDefaultTrustedProxies();

    @Nested
    @DisplayName("isTrustedProxy() — matching CIDR IPv4 e IPv6")
    class IsTrustedProxyTests {

        @Test
        @DisplayName("Reconoce loopback y redes privadas IPv4/IPv6 por defecto")
        void shouldTrustDefaultLoopbackAndPrivateRanges() {
            assertThat(defaultResolver.isTrustedProxy("127.0.0.1")).isTrue();
            assertThat(defaultResolver.isTrustedProxy("::1")).isTrue();
            assertThat(defaultResolver.isTrustedProxy("0:0:0:0:0:0:0:1")).isTrue();
            assertThat(defaultResolver.isTrustedProxy("[::1]")).isTrue();
            assertThat(defaultResolver.isTrustedProxy("10.0.0.99")).isTrue();
            assertThat(defaultResolver.isTrustedProxy("10.255.255.254")).isTrue();
            assertThat(defaultResolver.isTrustedProxy("172.16.0.1")).isTrue();
            assertThat(defaultResolver.isTrustedProxy("172.31.255.254")).isTrue();
            assertThat(defaultResolver.isTrustedProxy("192.168.1.10")).isTrue();
            assertThat(defaultResolver.isTrustedProxy("fc00::1")).isTrue();
            assertThat(defaultResolver.isTrustedProxy("fd12:3456:789a::1")).isTrue();
        }

        @Test
        @DisplayName("Reconoce direcciones IPv4 mapeadas en IPv6 (::ffff:x.x.x.x)")
        void shouldTrustIpv4MappedIpv6Addresses() {
            assertThat(defaultResolver.isTrustedProxy("::ffff:127.0.0.1")).isTrue();
            assertThat(defaultResolver.isTrustedProxy("::ffff:10.0.0.5")).isTrue();
            assertThat(defaultResolver.isTrustedProxy("::ffff:203.0.113.77")).isFalse();
        }

        @Test
        @DisplayName("Rechaza direcciones IP públicas o fuera de rango CIDR")
        void shouldRejectPublicAndOutOfRangeIps() {
            assertThat(defaultResolver.isTrustedProxy("203.0.113.77")).isFalse();
            assertThat(defaultResolver.isTrustedProxy("198.51.100.1")).isFalse();
            assertThat(defaultResolver.isTrustedProxy("172.15.255.255")).isFalse();
            assertThat(defaultResolver.isTrustedProxy("172.32.0.1")).isFalse();
            assertThat(defaultResolver.isTrustedProxy("127.0.0.2")).isFalse();
            assertThat(defaultResolver.isTrustedProxy("2001:db8::1")).isFalse();
        }

        @Test
        @DisplayName("Rechaza entradas nulas, vacías o malformadas sin lanzar excepciones")
        void shouldReturnFalseForNullBlankOrMalformedInputs() {
            assertThat(defaultResolver.isTrustedProxy(null)).isFalse();
            assertThat(defaultResolver.isTrustedProxy("")).isFalse();
            assertThat(defaultResolver.isTrustedProxy("   ")).isFalse();
            assertThat(defaultResolver.isTrustedProxy("not-an-ip")).isFalse();
            assertThat(defaultResolver.isTrustedProxy("999.999.999.999")).isFalse();
            assertThat(defaultResolver.isTrustedProxy("localhost")).isFalse();
        }

        @Test
        @DisplayName("Soporta rangos CIDR personalizados y direcciones sin prefijo")
        void shouldSupportCustomCidrsAndHostIps() {
            var resolver = new TrustedProxyResolver(List.of("198.51.100.0/24", "203.0.113.10"));

            assertThat(resolver.isTrustedProxy("198.51.100.42")).isTrue();
            assertThat(resolver.isTrustedProxy("198.51.101.42")).isFalse();
            assertThat(resolver.isTrustedProxy("203.0.113.10")).isTrue();
            assertThat(resolver.isTrustedProxy("203.0.113.11")).isFalse();
            assertThat(resolver.isTrustedProxy("127.0.0.1")).isFalse();
        }

        @Test
        @DisplayName("Lista vacía de proxies confiables rechaza todas las IPs")
        void shouldRejectAllWhenTrustedProxiesEmpty() {
            var resolver = new TrustedProxyResolver(List.of());

            assertThat(resolver.isTrustedProxy("127.0.0.1")).isFalse();
            assertThat(resolver.isTrustedProxy("10.0.0.1")).isFalse();
        }
    }

    @Nested
    @DisplayName("Validación de configuración CIDR en construcción")
    class CidrValidationTests {

        @Test
        @DisplayName("Falla al construir con CIDR inválido o prefijo fuera de rango")
        void shouldFailOnInvalidCidr() {
            assertThatThrownBy(() -> new TrustedProxyResolver(List.of("10.0.0.0/33")))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new TrustedProxyResolver(List.of("::1/129")))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new TrustedProxyResolver(List.of("10.0.0.0/-1")))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new TrustedProxyResolver(List.of("invalid-host/24")))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("extractClientIp() — protección anti-spoofing")
    class ExtractClientIpTests {

        @Test
        @DisplayName("Usa la primera IP de X-Forwarded-For cuando el proxy es confiable")
        void shouldExtractFirstForwardedIpFromTrustedProxy() {
            String ip =
                    defaultResolver.extractClientIp(
                            "10.0.0.99", " 203.0.113.195 , 70.41.3.18 ", "198.51.100.42");
            assertThat(ip).isEqualTo("203.0.113.195");
        }

        @Test
        @DisplayName("Usa X-Real-IP como respaldo cuando X-Forwarded-For está ausente")
        void shouldFallbackToXRealIpFromTrustedProxy() {
            String ip = defaultResolver.extractClientIp("127.0.0.1", "   ", " 198.51.100.42 ");
            assertThat(ip).isEqualTo("198.51.100.42");
        }

        @Test
        @DisplayName("Ignora X-Forwarded-For y X-Real-IP cuando remoteAddr es una IP pública")
        void shouldIgnoreSpoofedHeadersFromPublicIp() {
            String ip =
                    defaultResolver.extractClientIp(
                            "203.0.113.77", "198.51.100.10, 10.0.0.1", "198.51.100.20");
            assertThat(ip).isEqualTo("203.0.113.77");
        }

        @Test
        @DisplayName("Retorna UNKNOWN si remoteAddr es nulo o vacío")
        void shouldReturnUnknownWhenRemoteAddrMissing() {
            assertThat(defaultResolver.extractClientIp(null, "1.2.3.4", "5.6.7.8"))
                    .isEqualTo("UNKNOWN");
            assertThat(defaultResolver.extractClientIp("   ", "1.2.3.4", null))
                    .isEqualTo("UNKNOWN");
        }
    }
}
