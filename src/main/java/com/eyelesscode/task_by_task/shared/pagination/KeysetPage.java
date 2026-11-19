package com.eyelesscode.task_by_task.shared.pagination;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;

@Schema(description = "Página de resultados con paginación Keyset (Seek)")
public record KeysetPage<T>(
        @Schema(description = "Elementos de la página actual") List<T> content,
        @Schema(
                        description =
                                "Cursor opaco Base64URL para solicitar la siguiente página (nulo si"
                                        + " no hay más resultados)",
                        nullable = true)
                String nextCursor,
        @Schema(description = "Indica si existen más páginas disponibles") boolean hasNext,
        @Schema(description = "Cantidad de elementos devueltos en la página actual", example = "20")
                int size) {

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
