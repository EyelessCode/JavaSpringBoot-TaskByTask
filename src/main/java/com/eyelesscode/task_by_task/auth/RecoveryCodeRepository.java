package com.eyelesscode.task_by_task.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, Long> {

    List<RecoveryCode> findByUserIdAndUsedFalse(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rc FROM RecoveryCode rc WHERE rc.user.id = :userId AND rc.used = false")
    List<RecoveryCode> findUnusedForUpdate(@Param("userId") Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "UPDATE RecoveryCode rc SET rc.used = true, rc.usedAt = :usedAt WHERE rc.id = :id AND"
                    + " rc.used = false")
    int markAsUsedIfUnused(@Param("id") Long id, @Param("usedAt") Instant usedAt);

    long countByUserIdAndUsedFalse(Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM RecoveryCode rc WHERE rc.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
