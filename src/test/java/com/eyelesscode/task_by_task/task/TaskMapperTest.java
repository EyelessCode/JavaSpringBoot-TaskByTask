package com.eyelesscode.task_by_task.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.eyelesscode.task_by_task.group.Group;
import com.eyelesscode.task_by_task.shared.time.DateTimeProvider;
import com.eyelesscode.task_by_task.tag.dto.TagResponse;
import com.eyelesscode.task_by_task.task.dto.TaskResponse;
import com.eyelesscode.task_by_task.user.User;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskMapperTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final Instant BASE_TIME = Instant.parse("2026-11-01T10:00:00Z");
    private final DateTimeProvider dateTimeProvider = new DateTimeProvider(Clock.systemUTC());

    @Test
    void toResponseMapsAllFieldsAndPreservesTagOrder() {
        User creator = userWithId(10L);
        User assignee = userWithId(20L);
        Group group = groupWithId(5L, creator);

        Instant updatedAt = Instant.parse("2026-11-02T12:00:00Z");
        Task task =
                new Task(
                        group,
                        assignee,
                        "Preparar entrega",
                        "Revisar criterios",
                        false,
                        null,
                        LocalDate.of(2026, 11, 20),
                        LocalTime.of(15, 30),
                        BASE_TIME,
                        updatedAt);
        task.setId(99L);

        TagResponse tagA = new TagResponse(1L, 5L, "alpha", BASE_TIME);
        TagResponse tagB = new TagResponse(2L, 5L, "beta", BASE_TIME);
        Instant now = Instant.parse("2026-11-15T14:00:00Z");

        TaskResponse response =
                TaskMapper.toResponse(task, List.of(tagA, tagB), dateTimeProvider, BOGOTA, now);

        assertThat(response.id()).isEqualTo(99L);
        assertThat(response.groupId()).isEqualTo(5L);
        assertThat(response.assignedUserId()).isEqualTo(20L);
        assertThat(response.title()).isEqualTo("Preparar entrega");
        assertThat(response.description()).isEqualTo("Revisar criterios");
        assertThat(response.completed()).isFalse();
        assertThat(response.completedAt()).isNull();
        assertThat(response.overdue()).isFalse();
        assertThat(response.dueDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(response.dueTime()).isEqualTo(LocalTime.of(15, 30));
        assertThat(response.tags()).containsExactly(tagA, tagB);
        assertThat(response.createdAt()).isEqualTo(BASE_TIME);
        assertThat(response.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void toResponseHandlesNullAssignedUserAndNullTags() {
        User creator = userWithId(10L);
        Group group = groupWithId(5L, creator);
        Task task =
                new Task(
                        group,
                        null,
                        "Sin asignar",
                        null,
                        false,
                        null,
                        null,
                        null,
                        BASE_TIME,
                        BASE_TIME);
        task.setId(100L);

        TaskResponse response =
                TaskMapper.toResponse(
                        task,
                        null,
                        dateTimeProvider,
                        BOGOTA,
                        Instant.parse("2026-11-15T14:00:00Z"));

        assertThat(response.assignedUserId()).isNull();
        assertThat(response.tags()).isEmpty();
        assertThat(response.overdue()).isFalse();
    }

    @Test
    void toResponseComputesOverdueForPendingTasks() {
        User creator = userWithId(10L);
        Group group = groupWithId(5L, creator);
        Task task =
                new Task(
                        group,
                        null,
                        "Vencida",
                        null,
                        false,
                        null,
                        LocalDate.of(2026, 11, 14),
                        LocalTime.of(9, 0),
                        BASE_TIME,
                        BASE_TIME);
        task.setId(101L);

        Instant afterDue = Instant.parse("2026-11-14T15:00:00Z"); // 10:00 Bogota
        Instant beforeDue = Instant.parse("2026-11-14T13:00:00Z"); // 08:00 Bogota

        assertThat(
                        TaskMapper.toResponse(task, List.of(), dateTimeProvider, BOGOTA, afterDue)
                                .overdue())
                .isTrue();
        assertThat(
                        TaskMapper.toResponse(task, List.of(), dateTimeProvider, BOGOTA, beforeDue)
                                .overdue())
                .isFalse();
    }

    @Test
    void toResponseForcesOverdueFalseWhenTaskIsCompletedEvenWithPastDueDate() {
        User creator = userWithId(10L);
        Group group = groupWithId(5L, creator);
        Instant completedAt = Instant.parse("2026-11-15T12:00:00Z");
        Task task =
                new Task(
                        group,
                        null,
                        "Completada",
                        null,
                        true,
                        completedAt,
                        LocalDate.of(2026, 11, 10),
                        LocalTime.of(8, 0),
                        BASE_TIME,
                        completedAt);
        task.setId(102L);

        TaskResponse response =
                TaskMapper.toResponse(
                        task,
                        List.of(),
                        dateTimeProvider,
                        BOGOTA,
                        Instant.parse("2026-11-15T14:00:00Z"));

        assertThat(response.completed()).isTrue();
        assertThat(response.completedAt()).isEqualTo(completedAt);
        assertThat(response.overdue()).isFalse();
    }

    private static User userWithId(Long id) {
        User user =
                new User(
                        "test" + id + "@example.com",
                        "user" + id,
                        "Test User",
                        "hash",
                        "America/Bogota",
                        "24h",
                        BASE_TIME,
                        BASE_TIME);
        user.setId(id);
        return user;
    }

    private static Group groupWithId(Long id, User owner) {
        Group group = new Group(owner, "Personal", true, BASE_TIME, BASE_TIME);
        group.setId(id);
        return group;
    }
}
