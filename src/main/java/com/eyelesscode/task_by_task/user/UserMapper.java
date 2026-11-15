package com.eyelesscode.task_by_task.user;

import com.eyelesscode.task_by_task.user.dto.UserResponse;

public final class UserMapper {

    private UserMapper() {}

    public static UserResponse toResponse(User user, int remainingRecoveryCodes) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getUsername(),
                user.getDisplayName(),
                user.getTimezone(),
                user.getTimeFormat(),
                user.getCreatedAt(),
                remainingRecoveryCodes);
    }
}
