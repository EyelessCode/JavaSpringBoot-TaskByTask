package com.eyelesscode.task_by_task.shared.pagination;

import java.util.Locale;
import java.util.Objects;

public final class SqlLikePattern {

    public static final char ESCAPE_CHAR = '!';

    private SqlLikePattern() {}

    public static String escape(String literal) {
        Objects.requireNonNull(literal, "literal must not be null");
        return literal.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    public static String containsIgnoreCase(String trimmedInput) {
        Objects.requireNonNull(trimmedInput, "trimmedInput must not be null");
        return "%" + escape(trimmedInput.toLowerCase(Locale.ROOT)) + "%";
    }

    public static String startsWithIgnoreCase(String trimmedInput) {
        Objects.requireNonNull(trimmedInput, "trimmedInput must not be null");
        return escape(trimmedInput.toLowerCase(Locale.ROOT)) + "%";
    }
}
