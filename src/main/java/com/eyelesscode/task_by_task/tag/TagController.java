package com.eyelesscode.task_by_task.tag;

import com.eyelesscode.task_by_task.shared.pagination.KeysetPage;
import com.eyelesscode.task_by_task.tag.dto.CreateTagRequest;
import com.eyelesscode.task_by_task.tag.dto.TagResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups/{groupId}/tags")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping
    public ResponseEntity<KeysetPage<TagResponse>> listGroupTags(
            @PathVariable Long groupId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(tagService.listGroupTags(groupId, query, cursor, limit));
    }

    @PostMapping
    public ResponseEntity<TagResponse> createOrReuseTag(
            @PathVariable Long groupId, @Valid @RequestBody CreateTagRequest request) {
        TagService.TagCreationResult result = tagService.createOrReuseTag(groupId, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }

    @DeleteMapping("/{tagId}")
    public ResponseEntity<Void> deleteGroupTag(
            @PathVariable Long groupId, @PathVariable Long tagId) {
        tagService.deleteGroupTag(groupId, tagId);
        return ResponseEntity.noContent().build();
    }
}
