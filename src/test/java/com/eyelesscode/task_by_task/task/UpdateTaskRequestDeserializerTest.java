package com.eyelesscode.task_by_task.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eyelesscode.task_by_task.task.dto.PatchField;
import com.eyelesscode.task_by_task.task.dto.UpdateTaskRequest;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class UpdateTaskRequestDeserializerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void emptyObjectProducesAllOmittedFields() {
        UpdateTaskRequest req = objectMapper.readValue("{}", UpdateTaskRequest.class);

        assertThat(req.isEmptyPatch()).isTrue();
        assertThat(req.title()).isEqualTo(PatchField.omitted());
        assertThat(req.description()).isEqualTo(PatchField.omitted());
        assertThat(req.dueDate()).isEqualTo(PatchField.omitted());
        assertThat(req.dueTime()).isEqualTo(PatchField.omitted());
        assertThat(req.tags()).isEqualTo(PatchField.omitted());
    }

    @Test
    void distinguishesExplicitNullFromOmittedAndNormalizesWhitespace() {
        String json =
                """
                {
                  "title": "  Nuevo título  ",
                  "description": "   ",
                  "dueDate": null,
                  "tags": ["  uno ", null, "dos"]
                }
                """;

        UpdateTaskRequest req = objectMapper.readValue(json, UpdateTaskRequest.class);

        assertThat(req.isEmptyPatch()).isFalse();
        assertThat(req.title()).isEqualTo(PatchField.of("Nuevo título"));
        assertThat(req.description()).isEqualTo(PatchField.of(null));
        assertThat(req.dueDate()).isEqualTo(PatchField.of(null));
        assertThat(req.dueTime()).isEqualTo(PatchField.omitted());
        assertThat(req.tags().present()).isTrue();
        assertThat(req.tags().value()).containsExactly("uno", null, "dos");
    }

    @Test
    void parsesValidDatesTimesAndIgnoresUnknownServerManagedFields() {
        String json =
                """
                {
                  "id": 999,
                  "groupId": 888,
                  "assignedUserId": 777,
                  "completed": true,
                  "completedAt": "2026-01-01T00:00:00Z",
                  "overdue": true,
                  "createdAt": "2026-01-01T00:00:00Z",
                  "updatedAt": "2026-01-01T00:00:00Z",
                  "dueDate": "2026-11-25",
                  "dueTime": "14:30:00",
                  "tags": null
                }
                """;

        UpdateTaskRequest req = objectMapper.readValue(json, UpdateTaskRequest.class);

        assertThat(req.dueDate()).isEqualTo(PatchField.of(LocalDate.of(2026, 11, 25)));
        assertThat(req.dueTime()).isEqualTo(PatchField.of(LocalTime.of(14, 30)));
        assertThat(req.tags()).isEqualTo(PatchField.of(null));
    }

    @Test
    void rejectsInvalidJsonTypesAndEmptyDateOrTimeStrings() {
        assertInvalidJson("[]");
        assertInvalidJson("{\"title\": 123}");
        assertInvalidJson("{\"description\": false}");
        assertInvalidJson("{\"dueDate\": 12345}");
        assertInvalidJson("{\"dueDate\": \"\"}");
        assertInvalidJson("{\"dueDate\": \"not-a-date\"}");
        assertInvalidJson("{\"dueTime\": []}");
        assertInvalidJson("{\"dueTime\": \"\"}");
        assertInvalidJson("{\"dueTime\": \"25:99\"}");
        assertInvalidJson("{\"tags\": \"abc\"}");
        assertInvalidJson("{\"tags\": [123]}");
    }

    private void assertInvalidJson(String json) {
        assertThatThrownBy(() -> objectMapper.readValue(json, UpdateTaskRequest.class))
                .isInstanceOf(JacksonException.class);
    }
}
