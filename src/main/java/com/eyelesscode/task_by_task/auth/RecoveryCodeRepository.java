package com.eyelesscode.task_by_task.auth;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, Long> {

    List<RecoveryCode> findByUserIdAndUsedFalse(Long userId);

    long countByUserIdAndUsedFalse(Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM RecoveryCode rc WHERE rc.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
