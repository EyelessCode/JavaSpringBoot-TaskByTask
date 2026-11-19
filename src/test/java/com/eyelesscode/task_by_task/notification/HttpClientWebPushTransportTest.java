package com.eyelesscode.task_by_task.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HttpClientWebPushTransportTest {

    private HttpServer server;
    private String baseUrl;
    private final HttpClient httpClient =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Sends RFC 8291/8292 headers and body and maps 201, 404, 410, 500, and timeout")
    void sendsHeadersAndMapsStatusCodesAndTimeout() {
        AtomicReference<String> contentEncoding = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> ttl = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<byte[]> receivedBody = new AtomicReference<>();

        server.createContext(
                "/push/201",
                exchange -> {
                    contentEncoding.set(exchange.getRequestHeaders().getFirst("Content-Encoding"));
                    contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                    ttl.set(exchange.getRequestHeaders().getFirst("TTL"));
                    auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    receivedBody.set(exchange.getRequestBody().readAllBytes());
                    exchange.sendResponseHeaders(201, -1);
                    exchange.close();
                });
        server.createContext(
                "/push/404",
                exchange -> {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                });
        server.createContext(
                "/push/410",
                exchange -> {
                    exchange.sendResponseHeaders(410, -1);
                    exchange.close();
                });
        server.createContext(
                "/push/500",
                exchange -> {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                });
        server.createContext(
                "/push/slow",
                exchange -> {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.sendResponseHeaders(201, -1);
                    exchange.close();
                });
        server.start();

        HttpClientWebPushTransport transport =
                new HttpClientWebPushTransport(httpClient, Duration.ofSeconds(5));
        byte[] sampleCiphertext = "encrypted-bytes".getBytes(StandardCharsets.UTF_8);
        String sampleVapid = "vapid t=header.payload.sig, k=pubKey";

        WebPushTransport.DeliveryResult res201 =
                transport.send(baseUrl + "/push/201", sampleCiphertext, sampleVapid);
        assertThat(res201.outcome()).isEqualTo(WebPushTransport.DeliveryOutcome.DELIVERED_2XX);
        assertThat(res201.statusCode()).isEqualTo(201);
        assertThat(contentEncoding.get()).isEqualTo("aes128gcm");
        assertThat(contentType.get()).isEqualTo("application/octet-stream");
        assertThat(ttl.get()).isEqualTo("86400");
        assertThat(auth.get()).isEqualTo(sampleVapid);
        assertThat(receivedBody.get()).isEqualTo(sampleCiphertext);

        WebPushTransport.DeliveryResult res404 =
                transport.send(baseUrl + "/push/404", sampleCiphertext, sampleVapid);
        assertThat(res404.outcome()).isEqualTo(WebPushTransport.DeliveryOutcome.GONE_404_OR_410);
        assertThat(res404.statusCode()).isEqualTo(404);

        WebPushTransport.DeliveryResult res410 =
                transport.send(baseUrl + "/push/410", sampleCiphertext, sampleVapid);
        assertThat(res410.outcome()).isEqualTo(WebPushTransport.DeliveryOutcome.GONE_404_OR_410);
        assertThat(res410.statusCode()).isEqualTo(410);

        WebPushTransport.DeliveryResult res500 =
                transport.send(baseUrl + "/push/500", sampleCiphertext, sampleVapid);
        assertThat(res500.outcome()).isEqualTo(WebPushTransport.DeliveryOutcome.TRANSIENT_ERROR);
        assertThat(res500.statusCode()).isEqualTo(500);

        HttpClientWebPushTransport shortTimeoutTransport =
                new HttpClientWebPushTransport(httpClient, Duration.ofMillis(200));
        WebPushTransport.DeliveryResult resTimeout =
                shortTimeoutTransport.send(baseUrl + "/push/slow", sampleCiphertext, sampleVapid);
        assertThat(resTimeout.outcome())
                .isEqualTo(WebPushTransport.DeliveryOutcome.TRANSIENT_ERROR);
        assertThat(resTimeout.statusCode()).isEqualTo(-1);
    }
}
