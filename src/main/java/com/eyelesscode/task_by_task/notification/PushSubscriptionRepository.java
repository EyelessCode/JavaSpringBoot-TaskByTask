package com.eyelesscode.task_by_task.notification;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    Optional<PushSubscription> findByEndpoint(String endpoint);

    @Query("SELECT ps FROM PushSubscription ps JOIN FETCH ps.user WHERE ps.id = :id")
    Optional<PushSubscription> findByIdWithUser(@Param("id") Long id);

    List<PushSubscription> findAllByUserId(Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PushSubscription ps WHERE ps.id IN :ids")
    void deleteAllByIdIn(@Param("ids") Collection<Long> ids);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PushSubscription ps WHERE ps.user.id = :userId AND ps.endpoint = :endpoint")
    void deleteByUserIdAndEndpoint(
            @Param("userId") Long userId, @Param("endpoint") String endpoint);
}
