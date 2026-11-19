package com.eyelesscode.task_by_task.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TrustedForwardedHeaderFilterTest {

    private final TrustedForwardedHeaderFilter filter =
            new TrustedForwardedHeaderFilter(TrustedProxyResolver.withDefaultTrustedProxies());

    @Test
    @DisplayName(
            "Aplica X-Forwarded-Proto, Host y For cuando la petición llega desde un proxy"
                    + " confiable (10.0.0.10)")
    void shouldApplyForwardedHeadersFromTrustedPrivateProxy() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/auth/csrf");
        request.setScheme("http");
        request.setServerName("internal-backend");
        request.setServerPort(8080);
        request.setRemoteAddr("10.0.0.10");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "api.taskbytask.app");
        request.addHeader("X-Forwarded-Port", "443");
        request.addHeader("X-Forwarded-For", "198.51.100.25, 10.0.0.10");

        var response = new MockHttpServletResponse();
        var captured = new AtomicReference<HttpServletRequest>();

        filter.doFilter(request, response, (req, res) -> captured.set((HttpServletRequest) req));

        HttpServletRequest downstream = captured.get();
        assertThat(downstream).isNotNull();
        assertThat(downstream.isSecure()).isTrue();
        assertThat(downstream.getScheme()).isEqualTo("https");
        assertThat(downstream.getServerName()).isEqualTo("api.taskbytask.app");
        assertThat(downstream.getServerPort()).isEqualTo(443);
        assertThat(downstream.getRemoteAddr()).isEqualTo("198.51.100.25");
    }

    @Test
    @DisplayName("Aplica X-Forwarded-Proto cuando la petición llega desde loopback (127.0.0.1)")
    void shouldApplyForwardedHeadersFromLoopback() throws Exception {
        var request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setScheme("http");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-Proto", "https");

        var response = new MockHttpServletResponse();
        var captured = new AtomicReference<HttpServletRequest>();

        filter.doFilter(request, response, (req, res) -> captured.set((HttpServletRequest) req));

        HttpServletRequest downstream = captured.get();
        assertThat(downstream).isNotNull();
        assertThat(downstream.isSecure()).isTrue();
        assertThat(downstream.getScheme()).isEqualTo("https");
    }

    @Test
    @DisplayName(
            "Elimina cabeceras Forwarded, X-Forwarded-* y X-Real-IP cuando remoteAddr es una IP"
                    + " pública no confiable")
    void shouldStripSpoofedForwardedHeadersFromUntrustedPublicIp() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setScheme("http");
        request.setServerName("backend.local");
        request.setServerPort(8080);
        request.setRemoteAddr("203.0.113.50");
        request.addHeader("Forwarded", "for=198.51.100.1;proto=https");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "evil.example.com");
        request.addHeader("X-Forwarded-Port", "443");
        request.addHeader("X-Forwarded-For", "198.51.100.1");
        request.addHeader("X-Real-IP", "198.51.100.2");
        request.addHeader("X-Custom-Header", "keep-me");

        var response = new MockHttpServletResponse();
        var captured = new AtomicReference<HttpServletRequest>();

        filter.doFilter(request, response, (req, res) -> captured.set((HttpServletRequest) req));

        HttpServletRequest downstream = captured.get();
        assertThat(downstream).isNotNull();
        assertThat(downstream.isSecure()).isFalse();
        assertThat(downstream.getScheme()).isEqualTo("http");
        assertThat(downstream.getServerName()).isEqualTo("backend.local");
        assertThat(downstream.getServerPort()).isEqualTo(8080);
        assertThat(downstream.getRemoteAddr()).isEqualTo("203.0.113.50");

        assertThat(downstream.getHeader("Forwarded")).isNull();
        assertThat(downstream.getHeader("X-Forwarded-Proto")).isNull();
        assertThat(downstream.getHeader("X-Forwarded-Host")).isNull();
        assertThat(downstream.getHeader("X-Forwarded-Port")).isNull();
        assertThat(downstream.getHeader("X-Forwarded-For")).isNull();
        assertThat(downstream.getHeader("X-Real-IP")).isNull();
        assertThat(downstream.getIntHeader("X-Forwarded-Port")).isEqualTo(-1);
        assertThat(Collections.list(downstream.getHeaders("X-Forwarded-For"))).isEmpty();
        assertThat(Collections.list(downstream.getHeaderNames()))
                .contains("X-Custom-Header")
                .doesNotContain(
                        "Forwarded",
                        "X-Forwarded-Proto",
                        "X-Forwarded-Host",
                        "X-Forwarded-Port",
                        "X-Forwarded-For",
                        "X-Real-IP");
        assertThat(downstream.getHeader("X-Custom-Header")).isEqualTo("keep-me");
    }

    @Test
    @DisplayName("Respeta lista personalizada de proxies confiables")
    void shouldRespectCustomTrustedProxyList() throws Exception {
        var customFilter =
                new TrustedForwardedHeaderFilter(
                        new TrustedProxyResolver(List.of("198.51.100.0/24")));

        var request = new MockHttpServletRequest("GET", "/api/v1/auth/csrf");
        request.setRemoteAddr("10.0.0.10");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-For", "203.0.113.99");

        var response = new MockHttpServletResponse();
        var captured = new AtomicReference<HttpServletRequest>();

        customFilter.doFilter(
                request, response, (req, res) -> captured.set((HttpServletRequest) req));

        HttpServletRequest downstream = captured.get();
        assertThat(downstream.isSecure()).isFalse();
        assertThat(downstream.getHeader("X-Forwarded-For")).isNull();
        assertThat(downstream.getRemoteAddr()).isEqualTo("10.0.0.10");
    }
}
