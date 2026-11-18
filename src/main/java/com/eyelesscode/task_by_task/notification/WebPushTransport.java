package com.eyelesscode.task_by_task.notification;

/** Outbound HTTP transport port for sending encrypted Web Push messages to push gateways. */
public interface WebPushTransport {

    enum DeliveryOutcome {
        DELIVERED_2XX,
        GONE_404_OR_410,
        TRANSIENT_ERROR
    }

    record DeliveryResult(DeliveryOutcome outcome, int statusCode, String diagnosticMessage) {}

    DeliveryResult send(String endpoint, byte[] encryptedBody, String vapidAuthorizationHeader);
}
