package com.eyelesscode.task_by_task.notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository for {@link Notification} with Keyset pagination and retention queries. */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("SELECT n FROM Notification n JOIN FETCH n.user WHERE n.id = :id")
    Optional<Notification> findByIdWithUser(@Param("id") Long id);

    long countByUserIdAndReadFalse(Long userId);

    @Query(
            """
            SELECT n FROM Notification n
            WHERE n.user.id = :userId
              AND (:unreadOnly = false OR n.read = false)
            ORDER BY n.createdAt DESC, n.id DESC
            """)
    List<Notification> findFirstPage(
            @Param("userId") Long userId,
            @Param("unreadOnly") boolean unreadOnly,
            Pageable pageable);

    @Query(
            """
            SELECT n FROM Notification n
            WHERE n.user.id = :userId
              AND (:unreadOnly = false OR n.read = false)
              AND (n.createdAt < :cursorCreatedAt
                   OR (n.createdAt = :cursorCreatedAt AND n.id < :cursorId))
            ORDER BY n.createdAt DESC, n.id DESC
            """)
    List<Notification> findNextPage(
            @Param("userId") Long userId,
            @Param("unreadOnly") boolean unreadOnly,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Notification n SET n.read = true WHERE n.user.id = :userId AND n.read = false")
    int markAllAsReadByUserId(@Param("userId") Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Notification n WHERE n.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
