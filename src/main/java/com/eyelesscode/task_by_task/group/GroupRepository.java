package com.eyelesscode.task_by_task.group;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupRepository extends JpaRepository<Group, Long> {

    Optional<Group> findByCreatorIdAndIsPersonalTrue(Long creatorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM Group g WHERE g.id = :id")
    Optional<Group> findByIdForUpdate(@Param("id") Long id);

    @Query(
            """
            SELECT g FROM Group g
            WHERE g.creator.id = :creatorId
            ORDER BY g.isPersonal DESC, g.createdAt ASC, g.id ASC
            """)
    List<Group> findFirstPageByCreatorId(@Param("creatorId") Long creatorId, Pageable pageable);

    @Query(
            """
            SELECT g FROM Group g
            WHERE g.creator.id = :creatorId
              AND (
                   (:cursorIsPersonal = true AND (
                        g.isPersonal = false
                        OR (g.isPersonal = true AND g.createdAt > :cursorCreatedAt)
                        OR (g.isPersonal = true AND g.createdAt = :cursorCreatedAt AND g.id > :cursorId)
                   ))
                   OR
                   (:cursorIsPersonal = false AND (
                        (g.isPersonal = false AND g.createdAt > :cursorCreatedAt)
                        OR (g.isPersonal = false AND g.createdAt = :cursorCreatedAt AND g.id > :cursorId)
                   ))
              )
            ORDER BY g.isPersonal DESC, g.createdAt ASC, g.id ASC
            """)
    List<Group> findNextPageByCreatorId(
            @Param("creatorId") Long creatorId,
            @Param("cursorIsPersonal") boolean cursorIsPersonal,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);
}
