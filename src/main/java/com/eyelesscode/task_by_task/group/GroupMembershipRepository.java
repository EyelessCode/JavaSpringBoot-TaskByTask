package com.eyelesscode.task_by_task.group;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupMembershipRepository extends JpaRepository<GroupMembership, Long> {

    List<GroupMembership> findByUserId(Long userId);
}
