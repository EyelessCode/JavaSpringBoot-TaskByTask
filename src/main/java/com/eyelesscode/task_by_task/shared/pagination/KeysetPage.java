package com.eyelesscode.task_by_task.shared.pagination;

import java.util.List;
import java.util.Objects;

public record KeysetPage<T>(List<T> content, String nextCursor, boolean hasNext, int size) {

    public KeysetPage {
        content = content != null ? List.copyOf(content) : List.of();
    }

    public KeysetPage(List<T> content, String nextCursor, boolean hasNext) {
        this(content, nextCursor, hasNext, content != null ? content.size() : 0);
    }

    public static <T> KeysetPage<T> of(List<T> items, int requestedLimit, String nextCursor) {
        Objects.requireNonNull(items, "items must not be null");
        if (requestedLimit < 1) {
            throw new IllegalArgumentException("requestedLimit debe ser mayor a 0");
        }
        boolean hasNext = items.size() > requestedLimit;
        List<T> pageItems = hasNext ? items.subList(0, requestedLimit) : items;
        String cursor = hasNext ? nextCursor : null;
        return new KeysetPage<>(pageItems, cursor, hasNext, pageItems.size());
    }
}
