package com.eyelesscode.task_by_task.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eyelesscode.task_by_task.group.Group;
import com.eyelesscode.task_by_task.shared.time.DateTimeProvider;
import com.eyelesscode.task_by_task.task.Task;
import com.eyelesscode.task_by_task.task.TaskRepository;
import com.eyelesscode.task_by_task.user.User;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.json.JsonMapper;

class DueNotificationJobServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-18T12:00:00Z"); // 07:00 Bogota

    private TaskRepository taskRepository;
    private NotificationRepository notificationRepository;
    private PushSubscriptionRepository pushSubscriptionRepository;
    private NotificationContentFormatter contentFormatter;
    private VapidConfigurationState vapidConfigurationState;
    private WebPushEncryptionService encryptionService;
    private WebPushTransport webPushTransport;
    private DateTimeProvider dateTimeProvider;
    private DueNotificationJobService jobService;

    @BeforeEach
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        pushSubscriptionRepository = mock(PushSubscriptionRepository.class);
        contentFormatter = new NotificationContentFormatter();
        vapidConfigurationState = new VapidConfigurationState(new VapidProperties(false, null));
        encryptionService = new WebPushEncryptionService(JsonMapper.builder().build());
        webPushTransport = mock(WebPushTransport.class);
        dateTimeProvider = new DateTimeProvider(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        jobService =
                new DueNotificationJobService(
                        taskRepository,
                        notificationRepository,
                        pushSubscriptionRepository,
                        contentFormatter,
                        vapidConfigurationState,
                        encryptionService,
                        webPushTransport,
                        dateTimeProvider,
                        TransactionOperations.withoutTransaction());
    }

    @Test
    @DisplayName(
            "NTF-REQ-12: Malformed instance ID, missing task, completed task, or null dueDate"
                    + " returns empty no-op")
    void noOpOnMalformedMissingCompletedOrNullDueDate() {
        assertThat(jobService.processDueTaskJob(null)).isEmpty();
        assertThat(jobService.processDueTaskJob("invalid-id")).isEmpty();
        assertThat(jobService.processDueTaskJob("task-due-abc")).isEmpty();
        assertThat(jobService.processDueTaskJob("task-due-0")).isEmpty();

        when(taskRepository.findByIdForDueNotification(10L)).thenReturn(Optional.empty());
        assertThat(jobService.processDueTaskJob("task-due-10")).isEmpty();

        User creator = user(1L, "UTC", "24H");
        Group group = group(1L, creator, "Personal");

        Task completedTask =
                new Task(
                        group,
                        null,
                        "Completada",
                        null,
                        true,
                        FIXED_NOW,
                        LocalDate.of(2026, 11, 18),
                        null,
                        FIXED_NOW,
                        FIXED_NOW);
        completedTask.setId(11L);
        when(taskRepository.findByIdForDueNotification(11L)).thenReturn(Optional.of(completedTask));
        assertThat(jobService.processDueTaskJob("task-due-11")).isEmpty();

        Task noDueDateTask =
                new Task(
                        group,
                        null,
                        "Sin fecha",
                        null,
                        false,
                        null,
                        null,
                        null,
                        FIXED_NOW,
                        FIXED_NOW);
        noDueDateTask.setId(12L);
        when(taskRepository.findByIdForDueNotification(12L)).thenReturn(Optional.of(noDueDateTask));
        assertThat(jobService.processDueTaskJob("task-due-12")).isEmpty();

        verify(notificationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName(
            "NTF-REQ-13: Postponed dueDate, pre-09:00 AM local date-only, or future dueTime returns"
                    + " rescheduled Instant")
    void reschedulesWhenTriggerIsStrictlyAfterNowPlusOneSecond() {
        // FIXED_NOW is 2026-11-18T12:00:00Z -> 07:00:00 in America/Bogota (before 09:00 AM local)
        User bogotaUser = user(1L, "America/Bogota", "24H");
        Group group = group(1L, bogotaUser, "Personal");

        // 1. Date-only task for today in Bogota at 07:00 local -> reschedules to 09:00 Bogota
        // (14:00Z)
        Task todayPreNineAm =
                new Task(
                        group,
                        null,
                        "Hoy antes de las 9",
                        null,
                        false,
                        null,
                        LocalDate.of(2026, 11, 18),
                        null,
                        FIXED_NOW,
                        FIXED_NOW);
        todayPreNineAm.setId(20L);
        when(taskRepository.findByIdForDueNotification(20L))
                .thenReturn(Optional.of(todayPreNineAm));

        Optional<Instant> rescheduled1 = jobService.processDueTaskJob("task-due-20");
        assertThat(rescheduled1).contains(Instant.parse("2026-11-18T14:00:00Z"));

        // 2. Date+time task postponed to 15:00 Bogota (20:00Z) -> reschedules to 20:00Z
        Task futureTime =
                new Task(
                        group,
                        null,
                        "Mas tarde",
                        null,
                        false,
                        null,
                        LocalDate.of(2026, 11, 18),
                        LocalTime.of(15, 0),
                        FIXED_NOW,
                        FIXED_NOW);
        futureTime.setId(21L);
        when(taskRepository.findByIdForDueNotification(21L)).thenReturn(Optional.of(futureTime));

        Optional<Instant> rescheduled2 = jobService.processDueTaskJob("task-due-21");
        assertThat(rescheduled2).contains(Instant.parse("2026-11-18T20:00:00Z"));

        verify(notificationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName(
            "NTF-REQ-14 & RNF-20: Resolves assignedUser when present (using assignee"
                    + " timeFormat/timezone), or group.creator when null")
    void resolvesAssignedUserOrGroupCreatorAndPersistsFormattedNotification() {
        User creator = user(1L, "UTC", "24H");
        User assignee = user(2L, "UTC", "12H");
        Group group = group(5L, creator, "Equipo");

        // Task due 5 seconds ago (11:59:55Z on 2026-11-18) assigned to assignee (12H format)
        Task assignedTask =
                new Task(
                        group,
                        assignee,
                        "Revisar diseño",
                        null,
                        false,
                        null,
                        LocalDate.of(2026, 11, 18),
                        LocalTime.of(11, 59),
                        FIXED_NOW,
                        FIXED_NOW);
        assignedTask.setId(30L);
        when(taskRepository.findByIdForDueNotification(30L)).thenReturn(Optional.of(assignedTask));
        when(notificationRepository.saveAndFlush(any()))
                .thenAnswer(
                        inv -> {
                            Notification n = inv.getArgument(0);
                            n.setId(500L);
                            return n;
                        });
        when(pushSubscriptionRepository.findAllByUserId(2L)).thenReturn(List.of());

        Optional<Instant> res = jobService.processDueTaskJob("task-due-30");
        assertThat(res).isEmpty();

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getUser().getId()).isEqualTo(2L);
        assertThat(saved.getEventType()).isEqualTo(NotificationEventType.TASK_DUE);
        assertThat(saved.isRead()).isFalse();
        assertThat(saved.getContent())
                .isEqualTo(
                        "La tarea \"Revisar diseño\" del grupo \"Equipo\" vence el 18/11/2026 a"
                                + " las 11:59 AM.");
    }

    private static User user(Long id, String timezone, String timeFormat) {
        User u =
                new User(
                        "u" + id + "@example.com",
                        "user" + id,
                        "User " + id,
                        "hash",
                        timezone,
                        timeFormat,
                        FIXED_NOW,
                        FIXED_NOW);
        u.setId(id);
        return u;
    }

    private static Group group(Long id, User creator, String name) {
        Group g = new Group(creator, name, true, FIXED_NOW, FIXED_NOW);
        g.setId(id);
        return g;
    }
}
