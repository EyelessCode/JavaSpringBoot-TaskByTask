package com.eyelesscode.task_by_task.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eyelesscode.task_by_task.group.dto.CreateGroupRequest;
import com.eyelesscode.task_by_task.group.dto.GroupResponse;
import com.eyelesscode.task_by_task.group.dto.UpdateGroupRequest;
import com.eyelesscode.task_by_task.user.User;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GroupMapperTest {

    @Test
    @DisplayName(
            "GroupMapper.toResponse mapea todos los campos de Group hacia GroupResponse"
                    + " (GRP-REQ-01)")
    void shouldMapGroupEntityToGroupResponse() {
        Instant createdAt = Instant.parse("2026-11-15T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-11-15T11:30:00Z");

        User creator =
                new User(
                        "user@example.com",
                        "User123",
                        "User Name",
                        "{bcrypt}hash",
                        "UTC",
                        "24H",
                        createdAt,
                        createdAt);
        creator.setId(42L);

        Group group = new Group(creator, "Universidad", false, createdAt, updatedAt);
        group.setId(99L);

        GroupResponse response = GroupMapper.toResponse(group);

        assertThat(response.id()).isEqualTo(99L);
        assertThat(response.name()).isEqualTo("Universidad");
        assertThat(response.isPersonal()).isFalse();
        assertThat(response.creatorId()).isEqualTo(42L);
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("GroupMapper.toResponse lanza NullPointerException si el grupo es nulo")
    void shouldThrowNullPointerExceptionWhenGroupIsNull() {
        assertThatThrownBy(() -> GroupMapper.toResponse(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("group must not be null");
    }

    @Test
    @DisplayName(
            "CreateGroupRequest y UpdateGroupRequest recortan espacios externos con trim() en su"
                    + " constructor compacto (EC-01)")
    void shouldTrimWhitespaceInCreateAndUpdateGroupRequests() {
        CreateGroupRequest createReq = new CreateGroupRequest("   Proyectos 2026   ");
        assertThat(createReq.name()).isEqualTo("Proyectos 2026");

        CreateGroupRequest nullCreateReq = new CreateGroupRequest(null);
        assertThat(nullCreateReq.name()).isNull();

        UpdateGroupRequest updateReq = new UpdateGroupRequest("  Bandeja Personal  ");
        assertThat(updateReq.name()).isEqualTo("Bandeja Personal");

        UpdateGroupRequest nullUpdateReq = new UpdateGroupRequest(null);
        assertThat(nullUpdateReq.name()).isNull();
    }
}
