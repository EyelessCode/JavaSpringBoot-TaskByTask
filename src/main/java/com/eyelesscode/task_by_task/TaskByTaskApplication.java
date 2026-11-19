package com.eyelesscode.task_by_task;

import com.eyelesscode.task_by_task.shared.security.SecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(SecurityProperties.class)
public class TaskByTaskApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskByTaskApplication.class, args);
    }
}
