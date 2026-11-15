package com.eyelesscode.task_by_task.task;

import com.eyelesscode.task_by_task.shared.pagination.KeysetCursor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import org.springframework.stereotype.Repository;

/** Dynamic JPQL implementation of {@link TaskQueryRepository} using Keyset (Seek) pagination. */
@Repository
public class TaskQueryRepositoryImpl implements TaskQueryRepository {

    private static final String TIE_BREAKER =
            "(t.createdAt > :cursorCreatedAt OR (t.createdAt = :cursorCreatedAt AND t.id >"
                    + " :cursorId))";

    private final EntityManager entityManager;

    public TaskQueryRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<Task> findTasksByKeyset(
            Long creatorId,
            List<Long> groupIds,
            TaskStatusFilter status,
            String searchPattern,
            KeysetCursor.TaskCursor cursor,
            int limit) {
        StringBuilder jpql =
                new StringBuilder(
                        "SELECT t FROM Task t JOIN FETCH t.group g WHERE g.creator.id ="
                                + " :creatorId ");

        if (groupIds != null && !groupIds.isEmpty()) {
            jpql.append("AND g.id IN :groupIds ");
        }
        if (status == TaskStatusFilter.PENDING) {
            jpql.append("AND t.completed = false ");
        } else if (status == TaskStatusFilter.COMPLETED) {
            jpql.append("AND t.completed = true ");
        }
        if (searchPattern != null) {
            jpql.append("AND LOWER(t.title) LIKE :searchPattern ESCAPE '!' ");
        }
        if (cursor != null) {
            String sameCompletedSeek = buildSameCompletedSeek(cursor);
            if (status == TaskStatusFilter.ALL && !cursor.completed()) {
                jpql.append("AND (t.completed = true OR (t.completed = false AND ")
                        .append(sameCompletedSeek)
                        .append(")) ");
            } else {
                jpql.append("AND t.completed = :cursorCompleted AND ")
                        .append(sameCompletedSeek)
                        .append(" ");
            }
        }

        jpql.append(
                "ORDER BY t.completed ASC, t.dueDate ASC NULLS LAST, t.dueTime ASC NULLS LAST,"
                        + " t.createdAt ASC, t.id ASC");

        TypedQuery<Task> query = entityManager.createQuery(jpql.toString(), Task.class);
        query.setParameter("creatorId", creatorId);

        if (groupIds != null && !groupIds.isEmpty()) {
            query.setParameter("groupIds", groupIds);
        }
        if (searchPattern != null) {
            query.setParameter("searchPattern", searchPattern);
        }
        if (cursor != null) {
            if (!(status == TaskStatusFilter.ALL && !cursor.completed())) {
                query.setParameter("cursorCompleted", cursor.completed());
            }
            query.setParameter("cursorCreatedAt", cursor.createdAt());
            query.setParameter("cursorId", cursor.id());
            if (cursor.dueDate() != null) {
                query.setParameter("cursorDueDate", cursor.dueDate());
            }
            if (cursor.dueTime() != null) {
                query.setParameter("cursorDueTime", cursor.dueTime());
            }
        }

        query.setMaxResults(limit + 1);
        return query.getResultList();
    }

    private static String buildSameCompletedSeek(KeysetCursor.TaskCursor cursor) {
        if (cursor.dueDate() != null && cursor.dueTime() != null) {
            return "(t.dueDate > :cursorDueDate OR t.dueDate IS NULL OR (t.dueDate ="
                    + " :cursorDueDate AND (t.dueTime > :cursorDueTime OR t.dueTime IS NULL OR"
                    + " (t.dueTime = :cursorDueTime AND "
                    + TIE_BREAKER
                    + "))))";
        }
        if (cursor.dueDate() != null) {
            return "(t.dueDate > :cursorDueDate OR t.dueDate IS NULL OR (t.dueDate ="
                    + " :cursorDueDate AND t.dueTime IS NULL AND "
                    + TIE_BREAKER
                    + "))";
        }
        return "(t.dueDate IS NULL AND " + TIE_BREAKER + ")";
    }
}
