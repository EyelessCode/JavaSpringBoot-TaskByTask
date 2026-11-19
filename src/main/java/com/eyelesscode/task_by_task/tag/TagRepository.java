package com.eyelesscode.task_by_task.tag;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TagRepository extends JpaRepository<Tag, Long> {

    @Query("SELECT t FROM Tag t JOIN FETCH t.group g JOIN FETCH g.creator WHERE t.id = :tagId")
    Optional<Tag> findByIdWithGroupAndCreator(@Param("tagId") Long tagId);

    @Query(
            value =
                    "SELECT * FROM tags WHERE group_id = :groupId "
                            + "AND TRIM(LOWER(name)) = TRIM(LOWER(:name)) LIMIT 1",
            nativeQuery = true)
    Optional<Tag> findByGroupIdAndNormalizedName(
            @Param("groupId") Long groupId, @Param("name") String name);

    @Query(
            """
            SELECT t FROM Tag t
            WHERE t.group.id = :groupId
            ORDER BY t.name ASC, t.id ASC
            """)
    List<Tag> findFirstPageByGroupId(@Param("groupId") Long groupId, Pageable pageable);

    @Query(
            """
            SELECT t FROM Tag t
            WHERE t.group.id = :groupId
              AND (t.name > :cursorName OR (t.name = :cursorName AND t.id > :cursorId))
            ORDER BY t.name ASC, t.id ASC
            """)
    List<Tag> findNextPageByGroupId(
            @Param("groupId") Long groupId,
            @Param("cursorName") String cursorName,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    @Query(
            """
            SELECT t FROM Tag t
            WHERE t.group.id = :groupId
              AND LOWER(t.name) LIKE :prefixPattern ESCAPE '!'
            ORDER BY t.name ASC, t.id ASC
            """)
    List<Tag> findFirstPageByGroupIdAndPrefix(
            @Param("groupId") Long groupId,
            @Param("prefixPattern") String prefixPattern,
            Pageable pageable);

    @Query(
            """
            SELECT t FROM Tag t
            WHERE t.group.id = :groupId
              AND LOWER(t.name) LIKE :prefixPattern ESCAPE '!'
              AND (t.name > :cursorName OR (t.name = :cursorName AND t.id > :cursorId))
            ORDER BY t.name ASC, t.id ASC
            """)
    List<Tag> findNextPageByGroupIdAndPrefix(
            @Param("groupId") Long groupId,
            @Param("prefixPattern") String prefixPattern,
            @Param("cursorName") String cursorName,
            @Param("cursorId") Long cursorId,
            Pageable pageable);
}
