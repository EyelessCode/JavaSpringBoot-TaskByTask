package com.eyelesscode.task_by_task.tag.dto;

import java.time.Instant;

public record TagResponse(Long id, Long groupId, String name, Instant createdAt) {}
