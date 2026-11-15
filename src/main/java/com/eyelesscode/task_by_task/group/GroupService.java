package com.eyelesscode.task_by_task.group;

import com.eyelesscode.task_by_task.group.dto.CreateGroupRequest;
import com.eyelesscode.task_by_task.group.dto.GroupResponse;
import com.eyelesscode.task_by_task.group.dto.UpdateGroupRequest;
import com.eyelesscode.task_by_task.shared.error.BadRequestException;
import com.eyelesscode.task_by_task.shared.error.ResourceConflictException;
import com.eyelesscode.task_by_task.shared.error.ResourceNotFoundException;
import com.eyelesscode.task_by_task.shared.pagination.KeysetCursor;
import com.eyelesscode.task_by_task.shared.pagination.KeysetCursorCodec;
import com.eyelesscode.task_by_task.shared.pagination.KeysetPage;
import com.eyelesscode.task_by_task.shared.security.SecurityUtils;
import com.eyelesscode.task_by_task.shared.time.DateTimeProvider;
import com.eyelesscode.task_by_task.user.User;
import com.eyelesscode.task_by_task.user.UserRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupService {

    private static final int MIN_PAGE_LIMIT = 1;
    private static final int MAX_PAGE_LIMIT = 50;

    private final GroupRepository groupRepository;
    private final GroupMembershipRepository groupMembershipRepository;
    private final UserRepository userRepository;
    private final DateTimeProvider dateTimeProvider;
    private final KeysetCursorCodec keysetCursorCodec;
    private final JdbcTemplate jdbcTemplate;

    public GroupService(
            GroupRepository groupRepository,
            GroupMembershipRepository groupMembershipRepository,
            UserRepository userRepository,
            DateTimeProvider dateTimeProvider,
            KeysetCursorCodec keysetCursorCodec,
            JdbcTemplate jdbcTemplate) {
        this.groupRepository = groupRepository;
        this.groupMembershipRepository = groupMembershipRepository;
        this.userRepository = userRepository;
        this.dateTimeProvider = dateTimeProvider;
        this.keysetCursorCodec = keysetCursorCodec;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public GroupResponse createGroup(CreateGroupRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        Instant now = dateTimeProvider.now();
        Group savedGroup = groupRepository.save(new Group(user, request.name(), false, now, now));
        groupMembershipRepository.save(new GroupMembership(savedGroup, user, "CREATOR", now));

        return GroupMapper.toResponse(savedGroup);
    }

    @Transactional(readOnly = true)
    public GroupResponse getGroup(Long groupId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Group group =
                groupRepository
                        .findById(groupId)
                        .orElseThrow(() -> new ResourceNotFoundException("Grupo no encontrado"));

        if (!group.getCreator().getId().equals(userId)) {
            throw new AccessDeniedException("No tiene permisos para realizar esta acción");
        }

        return GroupMapper.toResponse(group);
    }

    @Transactional(readOnly = true)
    public KeysetPage<GroupResponse> listGroups(String cursor, int limit) {
        if (limit < MIN_PAGE_LIMIT || limit > MAX_PAGE_LIMIT) {
            throw new BadRequestException("El límite de paginación debe estar entre 1 y 50");
        }

        Long userId = SecurityUtils.getCurrentUserId();
        Pageable pageable = PageRequest.of(0, limit + 1);

        List<Group> groups;
        if (cursor == null) {
            groups = groupRepository.findFirstPageByCreatorId(userId, pageable);
        } else {
            KeysetCursor.GroupCursor decoded = keysetCursorCodec.decodeGroupCursor(cursor);
            groups =
                    groupRepository.findNextPageByCreatorId(
                            userId,
                            decoded.isPersonal(),
                            decoded.createdAt(),
                            decoded.id(),
                            pageable);
        }

        List<GroupResponse> responses = groups.stream().map(GroupMapper::toResponse).toList();
        String nextCursor = null;
        if (responses.size() > limit) {
            GroupResponse last = responses.get(limit - 1);
            nextCursor =
                    keysetCursorCodec.encodeGroupCursor(
                            last.isPersonal(), last.createdAt(), last.id());
        }

        return KeysetPage.of(responses, limit, nextCursor);
    }

    @Transactional
    public GroupResponse renameGroup(Long groupId, UpdateGroupRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        Group group =
                groupRepository
                        .findById(groupId)
                        .orElseThrow(() -> new ResourceNotFoundException("Grupo no encontrado"));

        if (!group.getCreator().getId().equals(userId)) {
            throw new AccessDeniedException("No tiene permisos para realizar esta acción");
        }

        group.setName(request.name());
        group.setUpdatedAt(dateTimeProvider.now());
        Group savedGroup = groupRepository.save(group);

        return GroupMapper.toResponse(savedGroup);
    }

    @Transactional
    public void deleteGroup(Long groupId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Group group =
                groupRepository
                        .findByIdForUpdate(groupId)
                        .orElseThrow(() -> new ResourceNotFoundException("Grupo no encontrado"));

        if (!group.getCreator().getId().equals(userId)) {
            throw new AccessDeniedException("No tiene permisos para realizar esta acción");
        }

        if (group.isPersonal()) {
            throw new ResourceConflictException(
                    "El grupo Personal no puede ser eliminado ni convertido en compartido");
        }

        jdbcTemplate.update(
                "DELETE FROM scheduled_tasks WHERE task_instance IN ("
                        + "SELECT 'task-due-' || t.id FROM tasks t WHERE t.group_id = ?)",
                groupId);

        groupRepository.delete(group);
        groupRepository.flush();
    }
}
