package com.eyelesscode.task_by_task.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PushSubscription ps WHERE ps.user.id = :userId AND ps.endpoint = :endpoint")
    void deleteByUserIdAndEndpoint(
            @Param("userId") Long userId, @Param("endpoint") String endpoint);
}
