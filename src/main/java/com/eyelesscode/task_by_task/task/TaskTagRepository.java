package com.eyelesscode.task_by_task.task;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskTagRepository extends JpaRepository<TaskTag, TaskTagId> {

    @Query(
            """
            SELECT tt FROM TaskTag tt
            JOIN FETCH tt.tag
            WHERE tt.task.id IN :taskIds
            ORDER BY tt.tag.name ASC, tt.tag.id ASC
            """)
    List<TaskTag> findByTaskIdsOrderByTagNameAndId(@Param("taskIds") Collection<Long> taskIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM TaskTag tt WHERE tt.task.id = :taskId")
    void deleteByTaskId(@Param("taskId") Long taskId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM TaskTag tt WHERE tt.task.id = :taskId AND tt.tag.id = :tagId")
    int deleteByTaskIdAndTagId(@Param("taskId") Long taskId, @Param("tagId") Long tagId);
}
