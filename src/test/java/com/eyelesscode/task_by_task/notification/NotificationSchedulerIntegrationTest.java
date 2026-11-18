package com.eyelesscode.task_by_task.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eyelesscode.task_by_task.group.Group;
import com.eyelesscode.task_by_task.group.GroupRepository;
import com.eyelesscode.task_by_task.shared.AbstractIntegrationTest;
import com.eyelesscode.task_by_task.task.Task;
import com.eyelesscode.task_by_task.task.TaskDueSchedulePort;
import com.eyelesscode.task_by_task.task.TaskRepository;
import com.eyelesscode.task_by_task.user.User;
import com.eyelesscode.task_by_task.user.UserRepository;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
        properties = {"db-scheduler.enabled=true", "db-scheduler.polling-interval=200ms"})
class NotificationSchedulerIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TaskDueSchedulePort taskDueSchedulePort;
    @Autowired private Scheduler scheduler;
    @Autowired private UserRepository userRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update(
                "DELETE FROM scheduled_tasks WHERE task_name = ?", TaskDueSchedulePort.TASK_NAME);
        userRepository.deleteAll();
    }

    @Test
    @DisplayName(
            "db-scheduler automatically picks up task-due-notification from TaskDueSchedulePort,"
                    + " persists notification, and removes completed job")
    void schedulerAutomaticallyExecutesDueNotificationAndRemovesCompletedJob() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        User user =
                userRepository.save(
                        new User(
                                "sched1@example.com",
                                "scheduser1",
                                "Sched User",
                                "hash",
                                "UTC",
                                "24H",
                                now,
                                now));
        Group group = groupRepository.save(new Group(user, "Personal", true, now, now));

        // Task due yesterday (already due -> should notify immediately when picked)
        Task dueTask =
                taskRepository.save(
                        new Task(
                                group,
                                null,
                                "Vence ya",
                                null,
                                false,
                                null,
                                LocalDate.now(ZoneOffset.UTC).minusDays(1),
                                null,
                                now,
                                now));

        taskDueSchedulePort.scheduleOrReschedule(dueTask.getId(), now.minusSeconds(1));

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(150))
                .untilAsserted(
                        () -> {
                            assertThat(
                                            notificationRepository.countByUserIdAndReadFalse(
                                                    user.getId()))
                                    .isEqualTo(1L);
                            assertThat(findScheduledExecutionTime(dueTask.getId())).isNull();
                        });
    }

    @Test
    @DisplayName(
            "db-scheduler reschedules postponed task (NTF-REQ-13) and executes"
                    + " notification-retention-cleanup (NTF-REQ-26)")
    void schedulerReschedulesPostponedTaskAndRunsRetentionCleanup() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        User user =
                userRepository.save(
                        new User(
                                "sched2@example.com",
                                "scheduser2",
                                "Sched User 2",
                                "hash",
                                "UTC",
                                "24H",
                                now,
                                now));
        Group group = groupRepository.save(new Group(user, "Personal", true, now, now));

        // 1. Task postponed to 2 days in the future, but scheduled_tasks row has past
        // execution_time
        Task postponedTask =
                taskRepository.save(
                        new Task(
                                group,
                                null,
                                "Pospuesta",
                                null,
                                false,
                                null,
                                LocalDate.now(ZoneOffset.UTC).plusDays(2),
                                null,
                                now,
                                now));

        taskDueSchedulePort.scheduleOrReschedule(postponedTask.getId(), now.minusSeconds(2));

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(150))
                .untilAsserted(
                        () -> {
                            Instant execTime = findScheduledExecutionTime(postponedTask.getId());
                            assertThat(execTime).isNotNull();
                            assertThat(execTime).isAfter(now.plus(1, ChronoUnit.DAYS));
                        });
        assertThat(notificationRepository.countByUserIdAndReadFalse(user.getId())).isZero();

        // 2. Verify notification-retention-cleanup is registered and purges 31-day-old
        // notifications when triggered
        Notification expired =
                notificationRepository.save(
                        new Notification(
                                user,
                                NotificationEventType.TASK_DUE,
                                "Antigua",
                                false,
                                now.minus(31, ChronoUnit.DAYS)));

        scheduler.reschedule(
                TaskInstanceId.of(
                        NotificationSchedulerConfig.RETENTION_TASK_NAME, RecurringTask.INSTANCE),
                Instant.now().minusSeconds(1));

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(150))
                .untilAsserted(
                        () ->
                                assertThat(notificationRepository.findById(expired.getId()))
                                        .isEmpty());
    }

    private Instant findScheduledExecutionTime(Long taskId) {
        List<Timestamp> rows =
                jdbcTemplate.queryForList(
                        "SELECT execution_time FROM scheduled_tasks WHERE task_name = ? AND"
                                + " task_instance = ?",
                        Timestamp.class,
                        TaskDueSchedulePort.TASK_NAME,
                        TaskDueSchedulePort.INSTANCE_PREFIX + taskId);
        return rows.isEmpty() ? null : rows.getFirst().toInstant();
    }
}
