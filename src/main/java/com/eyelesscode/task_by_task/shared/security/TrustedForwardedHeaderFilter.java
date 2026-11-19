package com.eyelesscode.task_by_task.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces the trusted reverse-proxy boundary before Spring Session and Spring Security execute
 * (HRD-REQ-03, EC-02). Delegates to {@link ForwardedHeaderFilter} only when {@code remoteAddr}
 * matches {@code app.security.trusted-proxies}; otherwise strips spoofed forwarding headers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TrustedForwardedHeaderFilter extends OncePerRequestFilter {

    private static final Set<String> STRIPPED_HEADERS =
            Set.of(
                    "forwarded",
                    "x-forwarded-for",
                    "x-forwarded-host",
                    "x-forwarded-port",
                    "x-forwarded-proto",
                    "x-forwarded-prefix",
                    "x-forwarded-ssl",
                    "x-real-ip");

    private final TrustedProxyResolver trustedProxyResolver;
    private final ForwardedHeaderFilter delegate = new ForwardedHeaderFilter();

    public TrustedForwardedHeaderFilter(TrustedProxyResolver trustedProxyResolver) {
        this.trustedProxyResolver =
                Objects.requireNonNull(
                        trustedProxyResolver, "trustedProxyResolver must not be null");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (trustedProxyResolver.isTrustedProxy(request.getRemoteAddr())) {
            delegate.doFilter(request, response, filterChain);
            return;
        }
        filterChain.doFilter(new StripForwardedHeadersRequestWrapper(request), response);
    }

    private static final class StripForwardedHeadersRequestWrapper
            extends HttpServletRequestWrapper {

        private StripForwardedHeadersRequestWrapper(HttpServletRequest request) {
            super(request);
        }

        private static boolean isStripped(String name) {
            return name != null && STRIPPED_HEADERS.contains(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public String getHeader(String name) {
            return isStripped(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return isStripped(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Enumeration<String> upstream = super.getHeaderNames();
            if (upstream == null) {
                return Collections.emptyEnumeration();
            }
            List<String> filtered =
                    Collections.list(upstream).stream().filter(name -> !isStripped(name)).toList();
            return Collections.enumeration(filtered);
        }

        @Override
        public int getIntHeader(String name) {
            return isStripped(name) ? -1 : super.getIntHeader(name);
        }

        @Override
        public long getDateHeader(String name) {
            return isStripped(name) ? -1L : super.getDateHeader(name);
        }
    }
}
