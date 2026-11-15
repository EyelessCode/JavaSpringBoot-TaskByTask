package com.eyelesscode.task_by_task.task;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, Long>, TaskQueryRepository {

    @Query("SELECT t FROM Task t JOIN FETCH t.group g JOIN FETCH g.creator WHERE t.id = :taskId")
    Optional<Task> findByIdWithGroupAndCreator(@Param("taskId") Long taskId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Task t JOIN FETCH t.group g JOIN FETCH g.creator WHERE t.id = :taskId")
    Optional<Task> findByIdForUpdate(@Param("taskId") Long taskId);
}
