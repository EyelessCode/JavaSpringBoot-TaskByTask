package com.eyelesscode.task_by_task.task;

import com.eyelesscode.task_by_task.shared.pagination.KeysetCursor;
import java.util.List;

/** Custom Keyset pagination query repository for tasks. */
public interface TaskQueryRepository {

    List<Task> findTasksByKeyset(
            Long creatorId,
            List<Long> groupIds,
            TaskStatusFilter status,
            String searchPattern,
            KeysetCursor.TaskCursor cursor,
            int limit);
}
