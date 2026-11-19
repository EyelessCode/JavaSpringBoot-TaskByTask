package com.eyelesscode.task_by_task.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RateLimitServiceTest {

    private RateLimitService service;

    @BeforeEach
    void setUp() {
        service = new RateLimitService(5, Duration.ofMinutes(1), 1_000, Duration.ofMinutes(10));
    }

    @Nested
    @DisplayName("consumeOrThrow() y tryConsume() (FND-REQ-32, FND-REQ-33, RNF-06)")
    class ConsumptionTests {

        @Test
        @DisplayName(
                "Permite exactamente 5 intentos consecutivos y el 6to lanza RateLimitException")
        void shouldAllowFiveAttemptsAndBlockSixth() {
            String clientKey = "192.168.1.50";

            // Los primeros 5 intentos deben pasar sin lanzar excepción
            for (int i = 1; i <= 5; i++) {
                assertThatCode(() -> service.consumeOrThrow(clientKey)).doesNotThrowAnyException();
            }

            // El 6to intento consecutivo debe ser bloqueado con RateLimitException
            assertThatThrownBy(() -> service.consumeOrThrow(clientKey))
                    .isInstanceOf(RateLimitException.class)
                    .satisfies(
                            ex -> {
                                RateLimitException rateEx = (RateLimitException) ex;
                                assertThat(rateEx.getRetryAfterSeconds()).isBetween(1L, 60L);
                                assertThat(rateEx.getMessage())
                                        .contains("Demasiadas solicitudes")
                                        .contains("segundos");
                            });
        }

        @Test
        @DisplayName("tryConsume() retorna true para los primeros 5 tokens y false para el 6to")
        void shouldReturnTrueForAllowedTokensAndFalseWhenExhausted() {
            String clientKey = "10.0.0.1";

            for (int i = 1; i <= 5; i++) {
                assertThat(service.tryConsume(clientKey)).isTrue();
            }

            assertThat(service.tryConsume(clientKey)).isFalse();
        }

        @Test
        @DisplayName("El agotamiento de una IP no afecta a otras IPs (aislamiento por partición)")
        void shouldIsolateRateLimitsBetweenDifferentClients() {
            String clientA = "192.168.1.100";
            String clientB = "192.168.1.200";

            // Agotar completamente los 5 intentos del cliente A
            for (int i = 1; i <= 5; i++) {
                service.consumeOrThrow(clientA);
            }

            // Cliente A bloqueado
            assertThatThrownBy(() -> service.consumeOrThrow(clientA))
                    .isInstanceOf(RateLimitException.class);

            // Cliente B tiene sus 5 tokens disponibles e intactos
            for (int i = 1; i <= 5; i++) {
                assertThatCode(() -> service.consumeOrThrow(clientB)).doesNotThrowAnyException();
            }
        }
    }

    @Nested
    @DisplayName("Resolución de IP del cliente y construcción de clave")
    class ClientIpResolutionTests {

        @Test
        @DisplayName(
                "Prioriza la primera IP de X-Forwarded-For cuando hay múltiples proxies"
                        + " intermedios")
        void shouldResolveFirstIpFromForwardedHeader() {
            var request = new MockHttpServletRequest();
            request.addHeader("X-Forwarded-For", "203.0.113.195, 70.41.3.18, 150.172.238.178");
            request.setRemoteAddr("10.0.0.99");

            String resolved = service.resolveClientIp(request);
            assertThat(resolved).isEqualTo("203.0.113.195");
        }

        @Test
        @DisplayName("Usa X-Real-IP cuando X-Forwarded-For no está presente")
        void shouldResolveRealIpHeaderWhenForwardedIsMissing() {
            var request = new MockHttpServletRequest();
            request.addHeader("X-Real-IP", "198.51.100.42");
            request.setRemoteAddr("10.0.0.99");

            String resolved = service.resolveClientIp(request);
            assertThat(resolved).isEqualTo("198.51.100.42");
        }

        @Test
        @DisplayName("Usa getRemoteAddr() como fallback cuando no hay cabeceras de proxy presentes")
        void shouldFallbackToRemoteAddr() {
            var request = new MockHttpServletRequest();
            request.setRemoteAddr("127.0.0.1");

            String resolved = service.resolveClientIp(request);
            assertThat(resolved).isEqualTo("127.0.0.1");
        }

        @Test
        @DisplayName("Retorna UNKNOWN si request es nulo o remoteAddr está vacío")
        void shouldHandleNullOrEmptyRequestGracefully() {
            assertThat(service.resolveClientIp(null)).isEqualTo("UNKNOWN");

            var emptyRequest = new MockHttpServletRequest();
            emptyRequest.setRemoteAddr("");
            assertThat(service.resolveClientIp(emptyRequest)).isEqualTo("UNKNOWN");
        }

        @Test
        @DisplayName("buildPartitionKey combina la IP resuelta y la acción especificada")
        void shouldBuildConsistentPartitionKey() {
            var request = new MockHttpServletRequest();
            request.setRemoteAddr("172.16.0.5");

            String key = service.buildPartitionKey(request, "login");
            assertThat(key).isEqualTo("172.16.0.5:login");
        }

        @Test
        @DisplayName(
                "Ignora X-Forwarded-For y X-Real-IP falsificados cuando remoteAddr es una IP"
                        + " pública no confiable (EC-02)")
        void shouldIgnoreSpoofedProxyHeadersFromPublicIp() {
            var request = new MockHttpServletRequest();
            request.setRemoteAddr("203.0.113.77");
            request.addHeader("X-Forwarded-For", "198.51.100.10, 10.0.0.1");
            request.addHeader("X-Real-IP", "198.51.100.20");

            assertThat(service.resolveClientIp(request)).isEqualTo("203.0.113.77");
            assertThat(service.buildPartitionKey(request, "login")).isEqualTo("203.0.113.77:login");
        }

        @Test
        @DisplayName(
                "No permite evadir el rate limit rotando X-Forwarded-For desde una IP pública"
                        + " directa")
        void shouldNotBypassRateLimitBySpoofingXForwardedFor() {
            for (int i = 1; i <= 5; i++) {
                var request = new MockHttpServletRequest();
                request.setRemoteAddr("203.0.113.77");
                request.addHeader("X-Forwarded-For", "198.51.100." + i);
                String key = service.buildPartitionKey(request, "login");
                assertThatCode(() -> service.consumeOrThrow(key)).doesNotThrowAnyException();
            }

            var sixthRequest = new MockHttpServletRequest();
            sixthRequest.setRemoteAddr("203.0.113.77");
            sixthRequest.addHeader("X-Forwarded-For", "198.51.100.99");
            String sixthKey = service.buildPartitionKey(sixthRequest, "login");

            assertThatThrownBy(() -> service.consumeOrThrow(sixthKey))
                    .isInstanceOf(RateLimitException.class);
        }
    }
}
