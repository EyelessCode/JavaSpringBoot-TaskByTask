package com.eyelesscode.task_by_task.task.dto;

/** Tri-state wrapper distinguishing omitted JSON fields from explicit {@code null} or values. */
public record PatchField<T>(boolean present, T value) {

    private static final PatchField<?> OMITTED = new PatchField<>(false, null);

    @SuppressWarnings("unchecked")
    public static <T> PatchField<T> omitted() {
        return (PatchField<T>) OMITTED;
    }

    public static <T> PatchField<T> of(T value) {
        return new PatchField<>(true, value);
    }
}
