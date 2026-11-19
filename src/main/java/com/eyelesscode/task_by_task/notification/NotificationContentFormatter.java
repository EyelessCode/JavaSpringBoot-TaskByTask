package com.eyelesscode.task_by_task.notification;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Formats localized Spanish due-notification messages complying with {@code RNF-14} ({@code
 * dd/MM/yyyy}), {@code RF-04} ({@code 24H}/{@code 12H}), and the 500-character column limit.
 */
@Component
public class NotificationContentFormatter {

    public static final int MAX_CONTENT_LENGTH = 500;
    public static final String PUSH_NOTIFICATION_TITLE = "Tarea por vencer";

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);
    private static final DateTimeFormatter TIME_24H_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter TIME_12H_FORMATTER =
            DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final Pattern LINE_BREAK_OR_TAB = Pattern.compile("[\\r\\n\\t]+");

    public String formatTaskDueContent(
            String taskTitle,
            String groupName,
            LocalDate dueDate,
            LocalTime dueTime,
            String recipientTimeFormat) {
        Objects.requireNonNull(taskTitle, "taskTitle must not be null");
        Objects.requireNonNull(groupName, "groupName must not be null");
        Objects.requireNonNull(dueDate, "dueDate must not be null");

        String cleanTitle = sanitizeInlineText(taskTitle);
        String cleanGroup = sanitizeInlineText(groupName);
        String dateFormatted = dueDate.format(DATE_FORMATTER);

        String message;
        if (dueTime == null) {
            message =
                    "La tarea \""
                            + cleanTitle
                            + "\" del grupo \""
                            + cleanGroup
                            + "\" vence el "
                            + dateFormatted
                            + ".";
        } else {
            boolean use12Hour = "12H".equalsIgnoreCase(recipientTimeFormat);
            String timeFormatted =
                    dueTime.format(use12Hour ? TIME_12H_FORMATTER : TIME_24H_FORMATTER);
            message =
                    "La tarea \""
                            + cleanTitle
                            + "\" del grupo \""
                            + cleanGroup
                            + "\" vence el "
                            + dateFormatted
                            + " a las "
                            + timeFormatted
                            + ".";
        }

        if (message.length() > MAX_CONTENT_LENGTH) {
            return message.substring(0, MAX_CONTENT_LENGTH - 3) + "...";
        }
        return message;
    }

    private static String sanitizeInlineText(String raw) {
        return LINE_BREAK_OR_TAB.matcher(raw).replaceAll(" ").trim();
    }
}
