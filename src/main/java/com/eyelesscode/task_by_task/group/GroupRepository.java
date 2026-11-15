package com.eyelesscode.task_by_task.group;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupRepository extends JpaRepository<Group, Long> {

    Optional<Group> findByCreatorIdAndIsPersonalTrue(Long creatorId);
}
