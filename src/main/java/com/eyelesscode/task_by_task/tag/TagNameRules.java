package com.eyelesscode.task_by_task.tag;

import com.eyelesscode.task_by_task.shared.error.ValidationErrorDetail;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class TagNameRules {

    public static final int MIN_LENGTH = 1;
    public static final int MAX_LENGTH = 30;
    public static final String NO_DELIMITERS_REGEX = "^[^,;]*$";
    private static final Pattern NO_DELIMITERS_PATTERN = Pattern.compile(NO_DELIMITERS_REGEX);

    public static final String REQUIRED_MESSAGE = "El nombre de la etiqueta es obligatorio";
    public static final String LENGTH_MESSAGE =
            "El nombre de la etiqueta debe tener entre 1 y 30 caracteres";
    public static final String DELIMITERS_MESSAGE =
            "El nombre de la etiqueta no puede contener comas ni punto y coma";

    private TagNameRules() {}

    public static void validateTagName(
            String trimmedOrNullName, String fieldName, List<ValidationErrorDetail> errors) {
        if (trimmedOrNullName == null || trimmedOrNullName.trim().isEmpty()) {
            errors.add(new ValidationErrorDetail(fieldName, REQUIRED_MESSAGE));
            return;
        }
        String trimmed = trimmedOrNullName.trim();
        if (trimmed.length() < MIN_LENGTH || trimmed.length() > MAX_LENGTH) {
            errors.add(new ValidationErrorDetail(fieldName, LENGTH_MESSAGE));
        }
        if (!NO_DELIMITERS_PATTERN.matcher(trimmed).matches()) {
            errors.add(new ValidationErrorDetail(fieldName, DELIMITERS_MESSAGE));
        }
    }

    public static void validateTagList(List<String> tags, List<ValidationErrorDetail> errors) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        for (int i = 0; i < tags.size(); i++) {
            validateTagName(tags.get(i), "tags[" + i + "]", errors);
        }
    }

    public static List<String> deduplicatePreservingFirstCase(List<String> validTrimmedTags) {
        if (validTrimmedTags == null || validTrimmedTags.isEmpty()) {
            return List.of();
        }
        Map<String, String> uniqueByNormalized = new LinkedHashMap<>();
        for (String raw : validTrimmedTags) {
            if (raw == null) {
                continue;
            }
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                uniqueByNormalized.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
            }
        }
        return new ArrayList<>(uniqueByNormalized.values());
    }
}
