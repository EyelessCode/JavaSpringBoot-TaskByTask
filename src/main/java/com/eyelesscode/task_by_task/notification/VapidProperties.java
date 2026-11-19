package com.eyelesscode.task_by_task.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Externalized configuration properties for Web Push and VAPID (RFC 8292). */
@ConfigurationProperties(prefix = "app.web-push")
public record VapidProperties(boolean enabled, VapidKeys vapid) {

    public record VapidKeys(String publicKey, String privateKey, String subject) {}
}
