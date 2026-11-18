package com.eyelesscode.task_by_task.notification;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** {@link HttpClient}-backed implementation of {@link WebPushTransport} with explicit timeouts. */
@Component
public class HttpClientWebPushTransport implements WebPushTransport {

    public static final String DEFAULT_TTL_SECONDS = "86400";
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    @Autowired
    public HttpClientWebPushTransport(HttpClient httpClient) {
        this(httpClient, DEFAULT_REQUEST_TIMEOUT);
    }

    HttpClientWebPushTransport(HttpClient httpClient, Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.requestTimeout =
                Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
    }

    @Override
    public DeliveryResult send(
            String endpoint, byte[] encryptedBody, String vapidAuthorizationHeader) {
        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(endpoint.trim()))
                            .timeout(requestTimeout)
                            .header("Content-Encoding", "aes128gcm")
                            .header("Content-Type", "application/octet-stream")
                            .header("TTL", DEFAULT_TTL_SECONDS)
                            .header("Authorization", vapidAuthorizationHeader)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(encryptedBody))
                            .build();

            HttpResponse<Void> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return new DeliveryResult(DeliveryOutcome.DELIVERED_2XX, status, "OK");
            }
            if (status == 404 || status == 410) {
                return new DeliveryResult(
                        DeliveryOutcome.GONE_404_OR_410, status, "Subscription expired or gone");
            }
            return new DeliveryResult(
                    DeliveryOutcome.TRANSIENT_ERROR, status, "Gateway returned HTTP " + status);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new DeliveryResult(
                    DeliveryOutcome.TRANSIENT_ERROR, -1, "Interrupted: " + ex.getMessage());
        } catch (IOException | RuntimeException ex) {
            return new DeliveryResult(
                    DeliveryOutcome.TRANSIENT_ERROR, -1, ex.getClass().getSimpleName());
        }
    }
}
