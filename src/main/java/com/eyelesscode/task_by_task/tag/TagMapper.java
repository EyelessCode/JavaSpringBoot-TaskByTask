package com.eyelesscode.task_by_task.tag;

import com.eyelesscode.task_by_task.tag.dto.TagResponse;
import java.util.Objects;

public final class TagMapper {

    private TagMapper() {}

    public static TagResponse toResponse(Tag tag) {
        Objects.requireNonNull(tag, "tag must not be null");
        return new TagResponse(
                tag.getId(), tag.getGroup().getId(), tag.getName(), tag.getCreatedAt());
    }
}
