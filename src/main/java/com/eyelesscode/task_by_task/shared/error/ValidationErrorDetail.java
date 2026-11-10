package com.eyelesscode.task_by_task.shared.error;

public record ValidationErrorDetail(String name, String reason) {

    public String field() {
        return name;
    }

    public String message() {
        return reason;
    }
}
