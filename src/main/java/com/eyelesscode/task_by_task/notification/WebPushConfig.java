package com.eyelesscode.task_by_task.notification;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring configuration registering VAPID properties and the shared Web Push {@link HttpClient}. */
@Configuration
@EnableConfigurationProperties(VapidProperties.class)
public class WebPushConfig {

    @Bean
    public HttpClient webPushHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }
}
