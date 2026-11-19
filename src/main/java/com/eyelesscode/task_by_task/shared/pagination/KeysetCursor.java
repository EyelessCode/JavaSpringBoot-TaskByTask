package com.eyelesscode.task_by_task.shared.pagination;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public sealed interface KeysetCursor
        permits KeysetCursor.TaskCursor,
                KeysetCursor.GroupCursor,
                KeysetCursor.TagCursor,
                KeysetCursor.NotificationCursor {

    Long id();

    record TaskCursor(
            Boolean completed, LocalDate dueDate, LocalTime dueTime, Instant createdAt, Long id)
            implements KeysetCursor {

        public TaskCursor {
            if (id == null) {
                throw new IllegalArgumentException("El id del cursor no puede ser nulo");
            }
            if (completed == null) {
                throw new IllegalArgumentException("completed no puede ser nulo");
            }
            if (createdAt == null) {
                throw new IllegalArgumentException("createdAt no puede ser nulo");
            }
            if (dueTime != null && dueDate == null) {
                throw new IllegalArgumentException("dueTime requiere dueDate");
            }
        }
    }

    record GroupCursor(Boolean isPersonal, Instant createdAt, Long id) implements KeysetCursor {

        public GroupCursor {
            if (id == null) {
                throw new IllegalArgumentException("El id del cursor no puede ser nulo");
            }
            if (isPersonal == null) {
                throw new IllegalArgumentException("isPersonal no puede ser nulo");
            }
            if (createdAt == null) {
                throw new IllegalArgumentException("createdAt no puede ser nulo");
            }
        }
    }

    record TagCursor(String name, Long id) implements KeysetCursor {

        public TagCursor {
            if (id == null) {
                throw new IllegalArgumentException("El id del cursor no puede ser nulo");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("El nombre del tag no puede ser nulo o vacío");
            }
        }
    }

    record NotificationCursor(Instant createdAt, Long id) implements KeysetCursor {

        public NotificationCursor {
            if (id == null) {
                throw new IllegalArgumentException("El id del cursor no puede ser nulo");
            }
            if (createdAt == null) {
                throw new IllegalArgumentException("createdAt no puede ser nulo");
            }
        }
    }
}
