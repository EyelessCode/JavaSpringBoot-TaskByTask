package com.eyelesscode.task_by_task.task;

import java.time.Instant;

/**
 * Port for scheduling, rescheduling, or canceling task due-date notifications in {@code
 * scheduled_tasks}.
 */
public interface TaskDueSchedulePort {

    String TASK_NAME = "task-due-notification";
    String INSTANCE_PREFIX = "task-due-";

    void scheduleOrReschedule(Long taskId, Instant executionTime);

    void cancel(Long taskId);
}
