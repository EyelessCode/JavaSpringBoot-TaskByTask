package com.eyelesscode.task_by_task.shared.error;

public class BadRequestException extends RuntimeException {

    public static final String DEFAULT_TITLE = "Solicitud inválida";

    private final String title;

    public BadRequestException(String message) {
        this(DEFAULT_TITLE, message);
    }

    public BadRequestException(String title, String message) {
        super(message);
        this.title = title != null && !title.isBlank() ? title : DEFAULT_TITLE;
    }

    public BadRequestException(String message, Throwable cause) {
        this(DEFAULT_TITLE, message, cause);
    }

    public BadRequestException(String title, String message, Throwable cause) {
        super(message, cause);
        this.title = title != null && !title.isBlank() ? title : DEFAULT_TITLE;
    }

    public String getTitle() {
        return title;
    }
}
