package com.eyelesscode.task_by_task.task;

import com.eyelesscode.task_by_task.group.Group;
import com.eyelesscode.task_by_task.group.GroupRepository;
import com.eyelesscode.task_by_task.shared.error.ResourceNotFoundException;
import com.eyelesscode.task_by_task.shared.security.SecurityUtils;
import com.eyelesscode.task_by_task.shared.time.DateTimeProvider;
import com.eyelesscode.task_by_task.tag.Tag;
import com.eyelesscode.task_by_task.tag.TagMapper;
import com.eyelesscode.task_by_task.tag.TagService;
import com.eyelesscode.task_by_task.tag.dto.TagResponse;
import com.eyelesscode.task_by_task.task.dto.CreateTaskRequest;
import com.eyelesscode.task_by_task.task.dto.TaskResponse;
import com.eyelesscode.task_by_task.user.User;
import com.eyelesscode.task_by_task.user.UserRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application service for task creation, retrieval, and due-date schedule synchronization. */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskTagRepository taskTagRepository;
    private final GroupRepository groupRepository;
    private final UserRepository userRepository;
    private final TagService tagService;
    private final TaskDueSchedulePort taskDueSchedulePort;
    private final DateTimeProvider dateTimeProvider;

    public TaskService(
            TaskRepository taskRepository,
            TaskTagRepository taskTagRepository,
            GroupRepository groupRepository,
            UserRepository userRepository,
            TagService tagService,
            TaskDueSchedulePort taskDueSchedulePort,
            DateTimeProvider dateTimeProvider) {
        this.taskRepository = taskRepository;
        this.taskTagRepository = taskTagRepository;
        this.groupRepository = groupRepository;
        this.userRepository = userRepository;
        this.tagService = tagService;
        this.taskDueSchedulePort = taskDueSchedulePort;
        this.dateTimeProvider = dateTimeProvider;
    }

    @Transactional
    public TaskResponse createTask(Long groupId, CreateTaskRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        Group group = loadOwnedGroup(groupId, userId);

        dateTimeProvider.validateDueDateAndTime(request.dueDate(), request.dueTime());

        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        ZoneId userZone = dateTimeProvider.parseZoneId(user.getTimezone());
        Instant now = dateTimeProvider.now().truncatedTo(ChronoUnit.MICROS);

        Task task =
                new Task(
                        group,
                        null,
                        request.title(),
                        request.description(),
                        false,
                        null,
                        request.dueDate(),
                        request.dueTime(),
                        now,
                        now);
        Task savedTask = taskRepository.save(task);

        List<Tag> resolvedTags =
                request.tags() != null && !request.tags().isEmpty()
                        ? tagService.resolveOrCreateTags(group, request.tags(), now)
                        : List.of();

        if (!resolvedTags.isEmpty()) {
            List<TaskTag> taskTags =
                    resolvedTags.stream().map(tag -> new TaskTag(savedTask, tag, now)).toList();
            taskTagRepository.saveAll(taskTags);
        }

        syncDueSchedule(savedTask, userZone, now);

        List<TagResponse> tagResponses =
                resolvedTags.stream()
                        .sorted(Comparator.comparing(Tag::getName).thenComparing(Tag::getId))
                        .map(TagMapper::toResponse)
                        .toList();

        return TaskMapper.toResponse(savedTask, tagResponses, dateTimeProvider, userZone, now);
    }

    @Transactional(readOnly = true)
    public TaskResponse getTask(Long taskId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Task task =
                taskRepository
                        .findByIdWithGroupAndCreator(taskId)
                        .orElseThrow(() -> new ResourceNotFoundException("Tarea no encontrada"));
        if (!task.getGroup().getCreator().getId().equals(userId)) {
            throw new AccessDeniedException("No tiene permisos para realizar esta acción");
        }

        ZoneId userZone = dateTimeProvider.parseZoneId(task.getGroup().getCreator().getTimezone());
        Instant now = dateTimeProvider.now().truncatedTo(ChronoUnit.MICROS);
        List<TagResponse> tags =
                taskTagRepository.findByTaskIdsOrderByTagNameAndId(List.of(task.getId())).stream()
                        .map(taskTag -> TagMapper.toResponse(taskTag.getTag()))
                        .toList();

        return TaskMapper.toResponse(task, tags, dateTimeProvider, userZone, now);
    }

    private Group loadOwnedGroup(Long groupId, Long userId) {
        Group group =
                groupRepository
                        .findById(groupId)
                        .orElseThrow(() -> new ResourceNotFoundException("Grupo no encontrado"));
        if (!group.getCreator().getId().equals(userId)) {
            throw new AccessDeniedException("No tiene permisos para realizar esta acción");
        }
        return group;
    }

    private void syncDueSchedule(Task task, ZoneId userZone, Instant now) {
        if (task.isCompleted()) {
            taskDueSchedulePort.cancel(task.getId());
            return;
        }
        Optional<Instant> triggerInstant =
                dateTimeProvider.calculateDueTriggerInstant(
                        task.getDueDate(), task.getDueTime(), userZone, now);
        if (triggerInstant.isPresent()) {
            taskDueSchedulePort.scheduleOrReschedule(
                    task.getId(), triggerInstant.get().truncatedTo(ChronoUnit.MICROS));
        } else {
            taskDueSchedulePort.cancel(task.getId());
        }
    }
}
