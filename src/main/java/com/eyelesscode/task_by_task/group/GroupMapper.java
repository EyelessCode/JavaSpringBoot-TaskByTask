package com.eyelesscode.task_by_task.group;

import com.eyelesscode.task_by_task.group.dto.GroupResponse;
import java.util.Objects;

public final class GroupMapper {

    private GroupMapper() {}

    public static GroupResponse toResponse(Group group) {
        Objects.requireNonNull(group, "group must not be null");
        return new GroupResponse(
                group.getId(),
                group.getName(),
                group.isPersonal(),
                group.getCreator().getId(),
                group.getCreatedAt(),
                group.getUpdatedAt());
    }
}
