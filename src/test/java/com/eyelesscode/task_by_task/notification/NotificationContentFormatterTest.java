package com.eyelesscode.task_by_task.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.eyelesscode.task_by_task.notification.dto.NotificationResponse;
import com.eyelesscode.task_by_task.user.User;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotificationContentFormatterTest {

    private final NotificationContentFormatter formatter = new NotificationContentFormatter();

    @Test
    @DisplayName(
            "Formats date+time tasks in 24H (HH:mm) and 12H (h:mm a AM/PM) with dd/MM/yyyy (RNF-14,"
                    + " RF-04)")
    void formatsDateTimeIn24hAnd12h() {
        String content24h =
                formatter.formatTaskDueContent(
                        "Entregar informe",
                        "Universidad",
                        LocalDate.of(2026, 11, 18),
                        LocalTime.of(14, 30),
                        "24H");
        assertThat(content24h)
                .isEqualTo(
                        "La tarea \"Entregar informe\" del grupo \"Universidad\" vence el"
                                + " 18/11/2026 a las 14:30.");

        String content12hPm =
                formatter.formatTaskDueContent(
                        "Entregar informe",
                        "Universidad",
                        LocalDate.of(2026, 11, 18),
                        LocalTime.of(14, 30),
                        "12H");
        assertThat(content12hPm)
                .isEqualTo(
                        "La tarea \"Entregar informe\" del grupo \"Universidad\" vence el"
                                + " 18/11/2026 a las 2:30 PM.");

        String content12hAm =
                formatter.formatTaskDueContent(
                        "Desayuno",
                        "Personal",
                        LocalDate.of(2026, 5, 3),
                        LocalTime.of(9, 5),
                        "12h");
        assertThat(content12hAm)
                .isEqualTo(
                        "La tarea \"Desayuno\" del grupo \"Personal\" vence el 03/05/2026 a las"
                                + " 9:05 AM.");
    }

    @Test
    @DisplayName("Formats date-only tasks without fabricating 00:00 or 09:00 (NTF-REQ-15)")
    void formatsDateOnlyWithoutFabricatingTime() {
        String content =
                formatter.formatTaskDueContent(
                        "Pagar servicios", "Personal", LocalDate.of(2026, 11, 20), null, "12H");

        assertThat(content)
                .isEqualTo(
                        "La tarea \"Pagar servicios\" del grupo \"Personal\" vence el 20/11/2026.")
                .doesNotContain("00:00", "09:00", "9:00", "a las");
    }

    @Test
    @DisplayName(
            "Normalizes line breaks/tabs and truncates safely to <= 500 characters with ellipsis"
                    + " (EC-09)")
    void normalizesLineBreaksAndTruncatesSafelyTo500Chars() {
        String multiline =
                formatter.formatTaskDueContent(
                        "Línea 1\r\nLínea 2\tFin",
                        "Grupo\nA",
                        LocalDate.of(2026, 11, 18),
                        null,
                        "24H");
        assertThat(multiline)
                .isEqualTo(
                        "La tarea \"Línea 1 Línea 2 Fin\" del grupo \"Grupo A\" vence el"
                                + " 18/11/2026.");

        String hugeTitle = "X".repeat(600);
        String truncated =
                formatter.formatTaskDueContent(
                        hugeTitle,
                        "Personal",
                        LocalDate.of(2026, 11, 18),
                        LocalTime.of(16, 45),
                        "24H");

        assertThat(truncated).hasSize(500).endsWith("...");
    }

    @Test
    @DisplayName("NotificationMapper maps Notification entity to NotificationResponse")
    void notificationMapperMapsAllFields() {
        Instant now = Instant.parse("2026-11-18T15:00:00Z");
        User user = new User("u@example.com", "user1", "User One", "hash", "UTC", "24H", now, now);
        Notification notification =
                new Notification(user, NotificationEventType.TASK_DUE, "Contenido", false, now);
        notification.setId(77L);

        NotificationResponse res = NotificationMapper.toResponse(notification);
        assertThat(res.id()).isEqualTo(77L);
        assertThat(res.eventType()).isEqualTo("TASK_DUE");
        assertThat(res.content()).isEqualTo("Contenido");
        assertThat(res.read()).isFalse();
        assertThat(res.createdAt()).isEqualTo(now);
    }
}
