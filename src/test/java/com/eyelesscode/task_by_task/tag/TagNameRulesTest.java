package com.eyelesscode.task_by_task.tag;

import static org.assertj.core.api.Assertions.assertThat;

import com.eyelesscode.task_by_task.shared.error.ValidationErrorDetail;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TagNameRulesTest {

    @Test
    @DisplayName("validateTagName valida obligatoriedad, longitud 1..30 y ausencia de ',' o ';'")
    void shouldValidateTagNameRules() {
        List<ValidationErrorDetail> errors = new ArrayList<>();

        TagNameRules.validateTagName("  Trabajo  ", "name", errors);
        assertThat(errors).isEmpty();

        TagNameRules.validateTagName("   ", "name", errors);
        TagNameRules.validateTagName(null, "name", errors);
        TagNameRules.validateTagName("A".repeat(31), "name", errors);
        TagNameRules.validateTagName("uno,dos", "name", errors);
        TagNameRules.validateTagName("uno;dos", "name", errors);

        assertThat(errors)
                .extracting(ValidationErrorDetail::reason)
                .containsExactly(
                        TagNameRules.REQUIRED_MESSAGE,
                        TagNameRules.REQUIRED_MESSAGE,
                        TagNameRules.LENGTH_MESSAGE,
                        TagNameRules.DELIMITERS_MESSAGE,
                        TagNameRules.DELIMITERS_MESSAGE);
    }

    @Test
    @DisplayName("validateTagList reporta errores indexados tags[i] incluyendo elementos nulos")
    void shouldValidateTagListWithIndexedFieldNames() {
        List<ValidationErrorDetail> errors = new ArrayList<>();
        TagNameRules.validateTagList(Arrays.asList("Valida", null, "mala,tag"), errors);

        assertThat(errors)
                .containsExactly(
                        new ValidationErrorDetail("tags[1]", TagNameRules.REQUIRED_MESSAGE),
                        new ValidationErrorDetail("tags[2]", TagNameRules.DELIMITERS_MESSAGE));
    }

    @Test
    @DisplayName(
            "deduplicatePreservingFirstCase deduplica ignorando mayúsculas/espacios y conserva el"
                    + " primer casing")
    void shouldDeduplicatePreservingFirstCase() {
        List<String> result =
                TagNameRules.deduplicatePreservingFirstCase(
                        List.of("  Urgente ", "urgente", "Casa", "URGENTE", "casa "));

        assertThat(result).containsExactly("Urgente", "Casa");
    }
}
