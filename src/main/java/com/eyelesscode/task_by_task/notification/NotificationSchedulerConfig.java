package com.eyelesscode.task_by_task.notification;

import com.eyelesscode.task_by_task.task.TaskDueSchedulePort;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.boot.autoconfigure.DbSchedulerAutoConfiguration;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerProperties;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerStarter;
import com.github.kagkarlsson.scheduler.stats.StatsRegistry;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.CustomTask;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers persistent {@code db-scheduler} tasks for task due-date notifications ({@code
 * task-due-notification}) and daily 30-day retention cleanup ({@code
 * notification-retention-cleanup}), bridging {@link DbSchedulerAutoConfiguration} for Spring Boot
 * 4.
 */
@Configuration
@EnableConfigurationProperties(DbSchedulerProperties.class)
public class NotificationSchedulerConfig {

    public static final String RETENTION_TASK_NAME = "notification-retention-cleanup";

    @Bean
    public CustomTask<Void> taskDueNotificationTask(DueNotificationJobService jobService) {
        return Tasks.custom(TaskDueSchedulePort.TASK_NAME, Void.class)
                .onDeadExecutionRevive()
                .execute(
                        (taskInstance, executionContext) -> {
                            Optional<Instant> rescheduled =
                                    jobService.processDueTaskJob(taskInstance.getId());
                            if (rescheduled.isPresent()) {
                                Instant nextInstant = rescheduled.get();
                                return (completion, operations) ->
                                        operations.reschedule(completion, nextInstant);
                            }
                            return new CompletionHandler.OnCompleteRemove<>();
                        });
    }

    @Bean
    public RecurringTask<Void> notificationRetentionTask(NotificationService notificationService) {
        return Tasks.recurring(
                        RETENTION_TASK_NAME, Schedules.daily(ZoneOffset.UTC, LocalTime.of(3, 0)))
                .execute(
                        (taskInstance, executionContext) ->
                                notificationService.purgeExpiredNotifications());
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean(Scheduler.class)
    @ConditionalOnProperty(
            value = "db-scheduler.enabled",
            havingValue = "true",
            matchIfMissing = true)
    public Scheduler scheduler(
            DbSchedulerProperties properties,
            DataSource dataSource,
            List<Task<?>> configuredTasks) {
        DbSchedulerAutoConfiguration delegate =
                new DbSchedulerAutoConfiguration(
                        properties, dataSource, configuredTasks, List.of(), List.of());
        return delegate.scheduler(delegate.noopCustomizer(), StatsRegistry.NOOP);
    }

    @Bean
    @ConditionalOnMissingBean(DbSchedulerStarter.class)
    @ConditionalOnProperty(
            value = "db-scheduler.enabled",
            havingValue = "true",
            matchIfMissing = true)
    public DbSchedulerStarter dbSchedulerStarter(
            DbSchedulerProperties properties,
            DataSource dataSource,
            List<Task<?>> configuredTasks,
            Scheduler scheduler) {
        DbSchedulerAutoConfiguration delegate =
                new DbSchedulerAutoConfiguration(
                        properties, dataSource, configuredTasks, List.of(), List.of());
        return delegate.dbSchedulerStarter(scheduler);
    }
}
