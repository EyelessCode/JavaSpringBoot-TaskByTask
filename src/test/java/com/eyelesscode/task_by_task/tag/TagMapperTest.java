package com.eyelesscode.task_by_task.tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eyelesscode.task_by_task.group.Group;
import com.eyelesscode.task_by_task.tag.dto.CreateTagRequest;
import com.eyelesscode.task_by_task.tag.dto.TagResponse;
import com.eyelesscode.task_by_task.user.User;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TagMapperTest {

    @Test
    @DisplayName("TagMapper.toResponse mapea exactamente todos los campos de Tag")
    void shouldMapTagToTagResponse() {
        Instant now = Instant.parse("2026-11-15T12:00:00Z");
        User creator =
                new User("user@example.com", "user1", "User 1", "hash", "UTC", "24H", now, now);
        creator.setId(10L);
        Group group = new Group(creator, "Personal", true, now, now);
        group.setId(20L);
        Tag tag = new Tag(group, "Urgente", now);
        tag.setId(30L);

        TagResponse response = TagMapper.toResponse(tag);

        assertThat(response.id()).isEqualTo(30L);
        assertThat(response.groupId()).isEqualTo(20L);
        assertThat(response.name()).isEqualTo("Urgente");
        assertThat(response.createdAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("TagMapper.toResponse rechaza entidad nula")
    void shouldRejectNullTag() {
        assertThatThrownBy(() -> TagMapper.toResponse(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("CreateTagRequest recorta espacios en constructor compacto")
    void shouldTrimNameInCreateTagRequest() {
        CreateTagRequest request = new CreateTagRequest("   Trabajo   ");
        assertThat(request.name()).isEqualTo("Trabajo");
    }
}
