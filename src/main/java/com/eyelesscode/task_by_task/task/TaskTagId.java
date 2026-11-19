package com.eyelesscode.task_by_task.task;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class TaskTagId implements Serializable {

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "tag_id", nullable = false)
    private Long tagId;

    protected TaskTagId() {}

    public TaskTagId(Long taskId, Long tagId) {
        this.taskId = taskId;
        this.tagId = tagId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Long getTagId() {
        return tagId;
    }

    public void setTagId(Long tagId) {
        this.tagId = tagId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TaskTagId that)) {
            return false;
        }
        return Objects.equals(taskId, that.taskId) && Objects.equals(tagId, that.tagId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, tagId);
    }
}
