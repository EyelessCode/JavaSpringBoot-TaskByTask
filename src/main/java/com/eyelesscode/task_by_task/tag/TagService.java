package com.eyelesscode.task_by_task.tag;

import com.eyelesscode.task_by_task.group.Group;
import com.eyelesscode.task_by_task.group.GroupRepository;
import com.eyelesscode.task_by_task.shared.error.BadRequestException;
import com.eyelesscode.task_by_task.shared.error.ResourceNotFoundException;
import com.eyelesscode.task_by_task.shared.pagination.KeysetCursor;
import com.eyelesscode.task_by_task.shared.pagination.KeysetCursorCodec;
import com.eyelesscode.task_by_task.shared.pagination.KeysetPage;
import com.eyelesscode.task_by_task.shared.pagination.SqlLikePattern;
import com.eyelesscode.task_by_task.shared.security.SecurityUtils;
import com.eyelesscode.task_by_task.shared.time.DateTimeProvider;
import com.eyelesscode.task_by_task.tag.dto.CreateTagRequest;
import com.eyelesscode.task_by_task.tag.dto.TagResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TagService {

    private static final int MIN_PAGE_LIMIT = 1;
    private static final int MAX_PAGE_LIMIT = 50;

    private final TagRepository tagRepository;
    private final GroupRepository groupRepository;
    private final KeysetCursorCodec keysetCursorCodec;
    private final DateTimeProvider dateTimeProvider;
    private final JdbcTemplate jdbcTemplate;

    public TagService(
            TagRepository tagRepository,
            GroupRepository groupRepository,
            KeysetCursorCodec keysetCursorCodec,
            DateTimeProvider dateTimeProvider,
            JdbcTemplate jdbcTemplate) {
        this.tagRepository = tagRepository;
        this.groupRepository = groupRepository;
        this.keysetCursorCodec = keysetCursorCodec;
        this.dateTimeProvider = dateTimeProvider;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record TagResolution(Tag tag, boolean created) {}

    public record TagCreationResult(TagResponse response, boolean created) {}

    @Transactional(readOnly = true)
    public KeysetPage<TagResponse> listGroupTags(
            Long groupId, String query, String cursor, int limit) {
        if (limit < MIN_PAGE_LIMIT || limit > MAX_PAGE_LIMIT) {
            throw new BadRequestException("El límite de paginación debe estar entre 1 y 50");
        }

        Group group = loadOwnedGroup(groupId, SecurityUtils.getCurrentUserId());
        Pageable pageable = PageRequest.of(0, limit + 1);
        String trimmedQuery = query != null ? query.trim() : "";

        KeysetCursor.TagCursor decoded =
                cursor != null ? keysetCursorCodec.decodeTagCursor(cursor) : null;

        List<Tag> tags;
        if (trimmedQuery.isEmpty()) {
            if (decoded == null) {
                tags = tagRepository.findFirstPageByGroupId(group.getId(), pageable);
            } else {
                tags =
                        tagRepository.findNextPageByGroupId(
                                group.getId(), decoded.name(), decoded.id(), pageable);
            }
        } else {
            String prefixPattern = SqlLikePattern.startsWithIgnoreCase(trimmedQuery);
            if (decoded == null) {
                tags =
                        tagRepository.findFirstPageByGroupIdAndPrefix(
                                group.getId(), prefixPattern, pageable);
            } else {
                tags =
                        tagRepository.findNextPageByGroupIdAndPrefix(
                                group.getId(),
                                prefixPattern,
                                decoded.name(),
                                decoded.id(),
                                pageable);
            }
        }

        List<TagResponse> responses = tags.stream().map(TagMapper::toResponse).toList();
        String nextCursor = null;
        if (responses.size() > limit) {
            TagResponse last = responses.get(limit - 1);
            nextCursor = keysetCursorCodec.encodeTagCursor(last.name(), last.id());
        }

        return KeysetPage.of(responses, limit, nextCursor);
    }

    @Transactional
    public TagCreationResult createOrReuseTag(Long groupId, CreateTagRequest request) {
        Group group = loadOwnedGroup(groupId, SecurityUtils.getCurrentUserId());
        Instant now = dateTimeProvider.now().truncatedTo(ChronoUnit.MICROS);
        TagResolution resolution = resolveOrCreateTag(group, request.name(), now);
        return new TagCreationResult(TagMapper.toResponse(resolution.tag()), resolution.created());
    }

    @Transactional
    public TagResolution resolveOrCreateTag(Group group, String trimmedName, Instant now) {
        Optional<Tag> existing =
                tagRepository.findByGroupIdAndNormalizedName(group.getId(), trimmedName);
        if (existing.isPresent()) {
            return new TagResolution(existing.get(), false);
        }

        Instant truncatedNow = now.truncatedTo(ChronoUnit.MICROS);
        List<Long> insertedIds =
                jdbcTemplate.queryForList(
                        "INSERT INTO tags (group_id, name, created_at) VALUES (?, ?, ?) "
                                + "ON CONFLICT (group_id, TRIM(LOWER(name))) DO NOTHING "
                                + "RETURNING id",
                        Long.class,
                        group.getId(),
                        trimmedName,
                        Timestamp.from(truncatedNow));

        if (!insertedIds.isEmpty()) {
            Tag created = tagRepository.findById(insertedIds.getFirst()).orElseThrow();
            return new TagResolution(created, true);
        }

        Tag winner =
                tagRepository
                        .findByGroupIdAndNormalizedName(group.getId(), trimmedName)
                        .orElseThrow();
        return new TagResolution(winner, false);
    }

    @Transactional
    public List<Tag> resolveOrCreateTags(Group group, List<String> rawTrimmedTags, Instant now) {
        List<String> uniqueNames = TagNameRules.deduplicatePreservingFirstCase(rawTrimmedTags);
        List<Tag> resolved = new ArrayList<>(uniqueNames.size());
        for (String tagName : uniqueNames) {
            resolved.add(resolveOrCreateTag(group, tagName, now).tag());
        }
        return resolved;
    }

    @Transactional
    public void deleteGroupTag(Long groupId, Long tagId) {
        Long userId = SecurityUtils.getCurrentUserId();
        loadOwnedGroup(groupId, userId);

        Tag tag =
                tagRepository
                        .findByIdWithGroupAndCreator(tagId)
                        .orElseThrow(() -> new ResourceNotFoundException("Etiqueta no encontrada"));

        if (!tag.getGroup().getCreator().getId().equals(userId)) {
            throw new AccessDeniedException("No tiene permisos para realizar esta acción");
        }
        if (!tag.getGroup().getId().equals(groupId)) {
            throw new ResourceNotFoundException("Etiqueta no encontrada");
        }

        tagRepository.delete(tag);
        tagRepository.flush();
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
}
