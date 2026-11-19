package com.eyelesscode.task_by_task.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableJdbcHttpSession(maxInactiveIntervalInSeconds = 604800)
public class SecurityConfig {

    private static final String API_CSP = "default-src 'none'; frame-ancestors 'none'";
    private static final String SWAGGER_CSP =
            "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self'"
                    + " 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'";
    private static final String PERMISSIONS_POLICY =
            "camera=(), microphone=(), geolocation=(), payment=()";
    private static final long HSTS_MAX_AGE_SECONDS = 31_536_000L;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            CookieCsrfTokenRepository csrfTokenRepository,
            @Value("${springdoc.swagger-ui.enabled:true}") boolean swaggerUiEnabled)
            throws Exception {

        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
        requestHandler.setCsrfRequestAttributeName(null);

        http.cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .headers(headers -> configureSecurityHeaders(headers, swaggerUiEnabled))
                .csrf(
                        csrf ->
                                csrf.csrfTokenRepository(csrfTokenRepository)
                                        .csrfTokenRequestHandler(requestHandler))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(authenticationEntryPoint)
                                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/v1/auth/register",
                                                "/api/v1/auth/login",
                                                "/api/v1/auth/recover-password")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/actuator/health",
                                                "/actuator/health/liveness",
                                                "/actuator/health/readiness")
                                        .permitAll()
                                        .requestMatchers("/actuator/**")
                                        .denyAll()
                                        .requestMatchers(
                                                "/v3/api-docs/**",
                                                "/swagger-ui/**",
                                                "/swagger-ui.html")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated());

        return http.build();
    }

    @Bean
    public CookieCsrfTokenRepository cookieCsrfTokenRepository(
            SecurityProperties securityProperties) {
        SecurityProperties.Cookie cookieProps = securityProperties.cookie();
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(
                builder -> {
                    builder.sameSite(cookieProps.sameSite());
                    if (cookieProps.secure()) {
                        builder.secure(true);
                    }
                    if (cookieProps.domain() != null) {
                        builder.domain(cookieProps.domain());
                    }
                });
        return repository;
    }

    private static void configureSecurityHeaders(
            HeadersConfigurer<HttpSecurity> headers, boolean swaggerUiEnabled) {
        headers.contentTypeOptions(Customizer.withDefaults())
                .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                .referrerPolicy(
                        referrer ->
                                referrer.policy(
                                        ReferrerPolicyHeaderWriter.ReferrerPolicy
                                                .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .permissionsPolicyHeader(permissions -> permissions.policy(PERMISSIONS_POLICY))
                .httpStrictTransportSecurity(
                        hsts -> hsts.maxAgeInSeconds(HSTS_MAX_AGE_SECONDS).includeSubDomains(true));

        if (swaggerUiEnabled) {
            PathPatternRequestMatcher.Builder matcherBuilder =
                    PathPatternRequestMatcher.withDefaults();
            RequestMatcher swaggerMatcher =
                    new OrRequestMatcher(
                            matcherBuilder.matcher("/swagger-ui/**"),
                            matcherBuilder.matcher("/swagger-ui.html"),
                            matcherBuilder.matcher("/v3/api-docs/**"));
            headers.addHeaderWriter(
                            new DelegatingRequestMatcherHeaderWriter(
                                    swaggerMatcher,
                                    new ContentSecurityPolicyHeaderWriter(SWAGGER_CSP)))
                    .addHeaderWriter(
                            new DelegatingRequestMatcherHeaderWriter(
                                    new NegatedRequestMatcher(swaggerMatcher),
                                    new ContentSecurityPolicyHeaderWriter(API_CSP)));
        } else {
            headers.addHeaderWriter(new ContentSecurityPolicyHeaderWriter(API_CSP));
        }
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(SecurityProperties securityProperties) {
        CorsConfiguration config = new CorsConfiguration();
        List<String> allowedOrigins = securityProperties.cors().allowedOrigins();
        if (!allowedOrigins.isEmpty()) {
            config.setAllowedOrigins(allowedOrigins);
        }
        config.setAllowCredentials(true);
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(
                List.of("Content-Type", "Accept", "X-XSRF-TOKEN", "X-Requested-With"));
        config.setExposedHeaders(List.of("Retry-After"));
        config.setMaxAge(securityProperties.cors().maxAgeSeconds());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public CookieSerializer cookieSerializer(SecurityProperties securityProperties) {
        SecurityProperties.Cookie cookieProps = securityProperties.cookie();
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("SESSION");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite(cookieProps.sameSite());
        if (cookieProps.domain() != null) {
            serializer.setDomainName(cookieProps.domain());
        }
        if (cookieProps.secure()) {
            serializer.setUseSecureCookie(true);
        }
        return serializer;
    }

    static final class CsrfCookieFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
