package com.eyelesscode.task_by_task.task.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Explicit partial-update payload for {@code PATCH /api/v1/tasks/{taskId}}. */
@JsonDeserialize(using = UpdateTaskRequestDeserializer.class)
public record UpdateTaskRequest(
        PatchField<String> title,
        PatchField<String> description,
        PatchField<LocalDate> dueDate,
        PatchField<LocalTime> dueTime,
        PatchField<List<String>> tags) {

    public UpdateTaskRequest {
        title = title != null ? title : PatchField.omitted();
        description = description != null ? description : PatchField.omitted();
        dueDate = dueDate != null ? dueDate : PatchField.omitted();
        dueTime = dueTime != null ? dueTime : PatchField.omitted();
        tags = tags != null ? tags : PatchField.omitted();
    }

    public boolean isEmptyPatch() {
        return !title.present()
                && !description.present()
                && !dueDate.present()
                && !dueTime.present()
                && !tags.present();
    }
}
