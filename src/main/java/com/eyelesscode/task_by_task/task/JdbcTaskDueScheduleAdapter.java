package com.eyelesscode.task_by_task.task;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * JDBC adapter writing directly to {@code scheduled_tasks} for {@code db-scheduler} task-due jobs.
 */
@Component
public class JdbcTaskDueScheduleAdapter implements TaskDueSchedulePort {

    private static final String UPSERT_SQL =
            """
            INSERT INTO scheduled_tasks (task_name, task_instance, execution_time, picked, version)
            VALUES (?, ?, ?, false, 1)
            ON CONFLICT (task_name, task_instance) DO UPDATE SET
                execution_time = EXCLUDED.execution_time,
                picked = false,
                picked_by = NULL,
                last_failure = NULL,
                consecutive_failures = NULL,
                version = scheduled_tasks.version + 1
            """;

    private static final String DELETE_SQL =
            "DELETE FROM scheduled_tasks WHERE task_name = ? AND task_instance = ?";

    private final JdbcTemplate jdbcTemplate;

    public JdbcTaskDueScheduleAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void scheduleOrReschedule(Long taskId, Instant executionTime) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(executionTime, "executionTime must not be null");
        jdbcTemplate.update(
                UPSERT_SQL, TASK_NAME, INSTANCE_PREFIX + taskId, Timestamp.from(executionTime));
    }

    @Override
    public void cancel(Long taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        jdbcTemplate.update(DELETE_SQL, TASK_NAME, INSTANCE_PREFIX + taskId);
    }
}
