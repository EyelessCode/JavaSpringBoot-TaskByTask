package com.eyelesscode.task_by_task.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eyelesscode.task_by_task.shared.AbstractIntegrationTest;
import com.eyelesscode.task_by_task.shared.ratelimit.RateLimitService;
import com.eyelesscode.task_by_task.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.test.web.support.WebTestUtils;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.session.Session;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class HardeningSecurityIntegrationTest extends AbstractIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private TrustedForwardedHeaderFilter trustedForwardedHeaderFilter;
    @Autowired private SessionRepositoryFilter<? extends Session> sessionRepositoryFilter;
    @Autowired private CookieCsrfTokenRepository cookieCsrfTokenRepository;
    @Autowired private RateLimitService rateLimitService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        WebTestUtils.setCsrfTokenRepository(
                new MockHttpServletRequest(context.getServletContext()), cookieCsrfTokenRepository);
        mockMvc =
                MockMvcBuilders.webAppContextSetup(context)
                        .addFilters(trustedForwardedHeaderFilter, sessionRepositoryFilter)
                        .apply(springSecurity())
                        .build();
        rateLimitService.clear();
        jdbcTemplate.execute("DELETE FROM SPRING_SESSION");
        userRepository.deleteAll();
    }

    @Nested
    @DisplayName("CORS (HRD-REQ-01, HRD-REQ-02, EC-01)")
    class CorsTests {

        @Test
        @DisplayName("Preflight OPTIONS desde origen permitido devuelve cabeceras CORS válidas")
        void shouldAllowPreflightFromConfiguredOrigin() throws Exception {
            mockMvc.perform(
                            options("/api/v1/auth/login")
                                    .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                                    .header(
                                            HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                            "Content-Type, X-XSRF-TOKEN"))
                    .andExpect(status().isOk())
                    .andExpect(
                            header().string(
                                            HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                                            "http://localhost:3000"))
                    .andExpect(
                            header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                    .andExpect(
                            header().string(
                                            HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                                            containsString("POST")))
                    .andExpect(
                            header().string(
                                            HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                                            containsString("PATCH")))
                    .andExpect(
                            header().string(
                                            HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                                            containsString("X-XSRF-TOKEN")));
        }

        @Test
        @DisplayName("Petición CORS desde origen permitido expone Retry-After")
        void shouldExposeRetryAfterOnAllowedOriginRequest() throws Exception {
            mockMvc.perform(
                            get("/api/v1/auth/csrf")
                                    .header(HttpHeaders.ORIGIN, "http://localhost:3000"))
                    .andExpect(status().isOk())
                    .andExpect(
                            header().string(
                                            HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                                            "http://localhost:3000"))
                    .andExpect(
                            header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                    .andExpect(
                            header().string(
                                            HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                                            containsString("Retry-After")));
        }

        @Test
        @DisplayName("Origen no permitido es rechazado sin Access-Control-Allow-Origin")
        void shouldRejectDisallowedOrigin() throws Exception {
            mockMvc.perform(
                            options("/api/v1/auth/login")
                                    .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }

    @Nested
    @DisplayName("Cabeceras HTTP defensivas y CSP (HRD-REQ-04)")
    class SecurityHeadersTests {

        @Test
        @DisplayName("Rutas API devuelven cabeceras defensivas y CSP estricta")
        void shouldReturnDefensiveHeadersAndStrictApiCsp() throws Exception {
            mockMvc.perform(get("/api/v1/auth/csrf"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(
                            header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                    .andExpect(
                            header().string(
                                            "Permissions-Policy",
                                            "camera=(), microphone=(), geolocation=(), payment=()"))
                    .andExpect(
                            header().string(
                                            "Content-Security-Policy",
                                            "default-src 'none'; frame-ancestors 'none'"));
        }

        @Test
        @DisplayName("Swagger UI recibe CSP acotada cuando springdoc está habilitado")
        void shouldReturnScopedCspForSwaggerUi() throws Exception {
            mockMvc.perform(get("/swagger-ui/index.html"))
                    .andExpect(status().isOk())
                    .andExpect(
                            header().string(
                                            "Content-Security-Policy",
                                            containsString("default-src 'self'")))
                    .andExpect(
                            header().string(
                                            "Content-Security-Policy",
                                            containsString("frame-ancestors 'none'")));
        }

        @Test
        @DisplayName("Emite HSTS únicamente bajo petición HTTPS confiable")
        void shouldEmitHstsOnlyBehindTrustedHttpsProxy() throws Exception {
            mockMvc.perform(
                            get("/api/v1/auth/csrf")
                                    .with(
                                            req -> {
                                                req.setRemoteAddr("10.0.0.10");
                                                return req;
                                            })
                                    .header("X-Forwarded-Proto", "https"))
                    .andExpect(status().isOk())
                    .andExpect(
                            header().string(
                                            "Strict-Transport-Security",
                                            containsString("max-age=31536000")))
                    .andExpect(
                            header().string(
                                            "Strict-Transport-Security",
                                            containsString("includeSubDomains")));

            mockMvc.perform(
                            get("/api/v1/auth/csrf")
                                    .with(
                                            req -> {
                                                req.setRemoteAddr("203.0.113.77");
                                                return req;
                                            })
                                    .header("X-Forwarded-Proto", "https"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Strict-Transport-Security"));
        }
    }

    @Nested
    @DisplayName(
            "Política unificada de cookies y proxy de confianza (HRD-REQ-03, HRD-REQ-05, EC-02)")
    class CookieAndProxyTests {

        @Test
        @DisplayName(
                "XSRF-TOKEN y SESSION marcan Secure tras proxy HTTPS confiable pero no ante"
                        + " X-Forwarded-Proto falsificado desde IP pública")
        void shouldSetSecureCookiesOnlyBehindTrustedHttpsProxy() throws Exception {
            MvcResult trustedCsrf =
                    mockMvc.perform(
                                    get("/api/v1/auth/csrf")
                                            .with(
                                                    req -> {
                                                        req.setRemoteAddr("10.0.0.10");
                                                        return req;
                                                    })
                                            .header("X-Forwarded-Proto", "https"))
                            .andExpect(status().isOk())
                            .andReturn();

            Cookie trustedXsrf = trustedCsrf.getResponse().getCookie("XSRF-TOKEN");
            assertThat(trustedXsrf).isNotNull();
            assertThat(trustedXsrf.isHttpOnly()).isFalse();
            assertThat(trustedXsrf.getSecure()).isTrue();
            assertThat(trustedXsrf.getAttribute("SameSite")).isEqualTo("Lax");

            MvcResult spoofedCsrf =
                    mockMvc.perform(
                                    get("/api/v1/auth/csrf")
                                            .with(
                                                    req -> {
                                                        req.setRemoteAddr("203.0.113.77");
                                                        return req;
                                                    })
                                            .header("X-Forwarded-Proto", "https"))
                            .andExpect(status().isOk())
                            .andReturn();

            Cookie spoofedXsrf = spoofedCsrf.getResponse().getCookie("XSRF-TOKEN");
            assertThat(spoofedXsrf).isNotNull();
            assertThat(spoofedXsrf.isHttpOnly()).isFalse();
            assertThat(spoofedXsrf.getSecure()).isFalse();
            assertThat(spoofedXsrf.getAttribute("SameSite")).isEqualTo("Lax");

            String registerPayload =
                    """
                    {
                      "email": "proxy@example.com",
                      "username": "ProxyUser",
                      "displayName": "Proxy User",
                      "password": "Password123",
                      "timezone": "UTC"
                    }
                    """;

            MvcResult registerBehindProxy =
                    mockMvc.perform(
                                    post("/api/v1/auth/register")
                                            .with(csrf())
                                            .with(
                                                    req -> {
                                                        req.setRemoteAddr("10.0.0.10");
                                                        return req;
                                                    })
                                            .header("X-Forwarded-Proto", "https")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(registerPayload))
                            .andExpect(status().isCreated())
                            .andReturn();

            List<String> sessionCookies =
                    registerBehindProxy.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
            assertThat(sessionCookies)
                    .anySatisfy(
                            headerVal ->
                                    assertThat(headerVal)
                                            .contains("SESSION=")
                                            .contains("HttpOnly")
                                            .contains("SameSite=Lax")
                                            .contains("Secure"));
        }
    }

    @Nested
    @DisplayName("Actuator endurecido (HRD-REQ-12, HRD-REQ-13)")
    class ActuatorHardeningTests {

        @Test
        @DisplayName("Expone únicamente health, liveness y readiness sin detalles internos")
        void shouldExposeOnlyHealthProbesWithoutDetails() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.components").doesNotExist());

            mockMvc.perform(get("/actuator/health/liveness"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));

            mockMvc.perform(get("/actuator/health/readiness"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        }

        @Test
        @DisplayName("Bloquea el resto de rutas /actuator/** tanto para anónimos como autenticados")
        void shouldDenyOtherActuatorEndpoints() throws Exception {
            mockMvc.perform(get("/actuator/env"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.title").value("No autenticado"));

            mockMvc.perform(get("/actuator/env").with(user("1").roles("USER")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value("Acceso denegado"));

            mockMvc.perform(get("/actuator/beans").with(user("1").roles("USER")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value("Acceso denegado"));
        }
    }
}
