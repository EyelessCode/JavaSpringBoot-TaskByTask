package com.eyelesscode.task_by_task.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class SecurityPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SecurityProperties.class)
    static class TestConfig {}

    @Test
    @DisplayName("Debe aplicar valores seguros por defecto cuando no hay configuración")
    void shouldApplySecureDefaultsWhenNothingConfigured() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    SecurityProperties props = context.getBean(SecurityProperties.class);
                    assertThat(props.cors().allowedOrigins()).isEmpty();
                    assertThat(props.cors().maxAgeSeconds()).isEqualTo(3600L);
                    assertThat(props.cookie().secure()).isTrue();
                    assertThat(props.cookie().sameSite()).isEqualTo("Lax");
                    assertThat(props.cookie().domain()).isNull();
                    assertThat(props.trustedProxies())
                            .containsExactly(
                                    "127.0.0.1/32",
                                    "::1/128",
                                    "10.0.0.0/8",
                                    "172.16.0.0/12",
                                    "192.168.0.0/16",
                                    "fc00::/7");
                });
    }

    @Test
    @DisplayName("Debe enlazar y normalizar propiedades válidas")
    void shouldBindAndNormalizeValidProperties() {
        contextRunner
                .withPropertyValues(
                        "app.security.cors.allowed-origins= http://localhost:3000"
                                + " ,,https://app.example.com",
                        "app.security.cors.max-age-seconds=600",
                        "app.security.cookie.secure=false",
                        "app.security.cookie.same-site=strict",
                        "app.security.cookie.domain=   ",
                        "app.security.trusted-proxies=10.0.0.0/8, ,::1/128")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            SecurityProperties props = context.getBean(SecurityProperties.class);
                            assertThat(props.cors().allowedOrigins())
                                    .containsExactly(
                                            "http://localhost:3000", "https://app.example.com");
                            assertThat(props.cors().maxAgeSeconds()).isEqualTo(600L);
                            assertThat(props.cookie().secure()).isFalse();
                            assertThat(props.cookie().sameSite()).isEqualTo("Strict");
                            assertThat(props.cookie().domain()).isNull();
                            assertThat(props.trustedProxies())
                                    .containsExactly("10.0.0.0/8", "::1/128");
                        });
    }

    @Test
    @DisplayName("Debe aceptar SameSite=None únicamente con cookie segura")
    void shouldAcceptSameSiteNoneWithSecureCookie() {
        contextRunner
                .withPropertyValues(
                        "app.security.cookie.secure=true",
                        "app.security.cookie.same-site=NONE",
                        "app.security.cookie.domain= example.com ")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            SecurityProperties props = context.getBean(SecurityProperties.class);
                            assertThat(props.cookie().sameSite()).isEqualTo("None");
                            assertThat(props.cookie().domain()).isEqualTo("example.com");
                        });
    }

    @Test
    @DisplayName("Debe fallar el arranque cuando CORS contiene un comodín '*'")
    void shouldFailStartupWhenCorsContainsWildcard() {
        contextRunner
                .withPropertyValues("app.security.cors.allowed-origins=http://localhost:3000,*")
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .rootCause()
                                        .isInstanceOf(IllegalStateException.class)
                                        .hasMessageContaining("*"));
    }

    @Test
    @DisplayName("Debe fallar el arranque cuando SameSite=None se usa sin cookie segura")
    void shouldFailStartupWhenSameSiteNoneWithoutSecure() {
        contextRunner
                .withPropertyValues(
                        "app.security.cookie.secure=false", "app.security.cookie.same-site=None")
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .rootCause()
                                        .isInstanceOf(IllegalStateException.class)
                                        .hasMessageContaining("SameSite=None"));
    }

    @Test
    @DisplayName("Debe fallar el arranque con un valor SameSite no soportado")
    void shouldFailStartupWhenSameSiteInvalid() {
        contextRunner
                .withPropertyValues("app.security.cookie.same-site=Relaxed")
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .rootCause()
                                        .isInstanceOf(IllegalStateException.class)
                                        .hasMessageContaining("SameSite"));
    }

    @Test
    @DisplayName("Debe rechazar un max-age de CORS negativo")
    void shouldRejectNegativeCorsMaxAge() {
        assertThatThrownBy(() -> new SecurityProperties.Cors(List.of(), -1L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Debe devolver listas inmutables")
    void shouldExposeImmutableLists() {
        SecurityProperties props = new SecurityProperties(null, null, null);
        assertThatThrownBy(() -> props.trustedProxies().add("1.1.1.1/32"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> props.cors().allowedOrigins().add("http://x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
