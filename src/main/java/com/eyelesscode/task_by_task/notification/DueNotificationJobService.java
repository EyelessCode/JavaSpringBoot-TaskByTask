package com.eyelesscode.task_by_task.notification;

import com.eyelesscode.task_by_task.shared.time.DateTimeProvider;
import com.eyelesscode.task_by_task.task.Task;
import com.eyelesscode.task_by_task.task.TaskDueSchedulePort;
import com.eyelesscode.task_by_task.task.TaskRepository;
import com.eyelesscode.task_by_task.user.User;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestrates due-task notification jobs with strict transaction isolation (TX1 DB -> non-TX HTTP
 * Web Push -> TX2 stale cleanup) and postponement detection ({@code NTF-REQ-11..18}).
 */
@Service
public class DueNotificationJobService {

    private static final Logger log = LoggerFactory.getLogger(DueNotificationJobService.class);

    private final TaskRepository taskRepository;
    private final NotificationRepository notificationRepository;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final NotificationContentFormatter contentFormatter;
    private final VapidConfigurationState vapidConfigurationState;
    private final WebPushEncryptionService encryptionService;
    private final WebPushTransport webPushTransport;
    private final DateTimeProvider dateTimeProvider;
    private final TransactionOperations transactionOperations;

    @Autowired
    public DueNotificationJobService(
            TaskRepository taskRepository,
            NotificationRepository notificationRepository,
            PushSubscriptionRepository pushSubscriptionRepository,
            NotificationContentFormatter contentFormatter,
            VapidConfigurationState vapidConfigurationState,
            WebPushEncryptionService encryptionService,
            WebPushTransport webPushTransport,
            DateTimeProvider dateTimeProvider,
            PlatformTransactionManager transactionManager) {
        this(
                taskRepository,
                notificationRepository,
                pushSubscriptionRepository,
                contentFormatter,
                vapidConfigurationState,
                encryptionService,
                webPushTransport,
                dateTimeProvider,
                new TransactionTemplate(transactionManager));
    }

    DueNotificationJobService(
            TaskRepository taskRepository,
            NotificationRepository notificationRepository,
            PushSubscriptionRepository pushSubscriptionRepository,
            NotificationContentFormatter contentFormatter,
            VapidConfigurationState vapidConfigurationState,
            WebPushEncryptionService encryptionService,
            WebPushTransport webPushTransport,
            DateTimeProvider dateTimeProvider,
            TransactionOperations transactionOperations) {
        this.taskRepository = Objects.requireNonNull(taskRepository);
        this.notificationRepository = Objects.requireNonNull(notificationRepository);
        this.pushSubscriptionRepository = Objects.requireNonNull(pushSubscriptionRepository);
        this.contentFormatter = Objects.requireNonNull(contentFormatter);
        this.vapidConfigurationState = Objects.requireNonNull(vapidConfigurationState);
        this.encryptionService = Objects.requireNonNull(encryptionService);
        this.webPushTransport = Objects.requireNonNull(webPushTransport);
        this.dateTimeProvider = Objects.requireNonNull(dateTimeProvider);
        this.transactionOperations = Objects.requireNonNull(transactionOperations);
    }

    record SubscriptionSnapshot(Long id, String endpoint, String p256dh, String auth) {}

    private sealed interface TxOutcome {
        record NoOp() implements TxOutcome {}

        record Reschedule(Instant nextExecutionTime) implements TxOutcome {}

        record Dispatched(
                WebPushPayload payload, List<SubscriptionSnapshot> subscriptions, Instant now)
                implements TxOutcome {}
    }

    /**
     * Executes the due-notification workflow for {@code taskInstanceId}.
     *
     * @return non-empty {@link Instant} if the job should be rescheduled to a future time ({@code
     *     NTF-REQ-13}), or {@link Optional#empty()} if the job completed and should be removed.
     */
    public Optional<Instant> processDueTaskJob(String taskInstanceId) {
        Long taskId = parseTaskId(taskInstanceId);
        if (taskId == null) {
            log.warn(
                    "Identificador de instancia de job inválido '{}'; se descarta el job"
                            + " (NTF-REQ-12).",
                    taskInstanceId);
            return Optional.empty();
        }

        TxOutcome outcome = transactionOperations.execute(status -> executeTx1(taskId));
        if (outcome == null || outcome instanceof TxOutcome.NoOp) {
            return Optional.empty();
        }
        if (outcome instanceof TxOutcome.Reschedule(Instant nextExecutionTime)) {
            log.debug(
                    "Tarea {} pospuesta a futuro; se reprograma job para {}.",
                    taskId,
                    nextExecutionTime);
            return Optional.of(nextExecutionTime);
        }

        TxOutcome.Dispatched dispatched = (TxOutcome.Dispatched) outcome;
        dispatchWebPushAndCleanStale(dispatched);
        return Optional.empty();
    }

    private TxOutcome executeTx1(Long taskId) {
        Optional<Task> taskOpt = taskRepository.findByIdForDueNotification(taskId);
        if (taskOpt.isEmpty()) {
            log.debug("Tarea {} no existe al ejecutar job de vencimiento; no-op.", taskId);
            return new TxOutcome.NoOp();
        }

        Task task = taskOpt.get();
        if (task.isCompleted() || task.getDueDate() == null) {
            log.debug("Tarea {} ya está completada o sin fecha de vencimiento; no-op.", taskId);
            return new TxOutcome.NoOp();
        }

        User recipient =
                task.getAssignedUser() != null
                        ? task.getAssignedUser()
                        : task.getGroup().getCreator();
        ZoneId recipientZone = dateTimeProvider.parseZoneId(recipient.getTimezone());
        Instant now = dateTimeProvider.now().truncatedTo(ChronoUnit.MICROS);

        Optional<Instant> triggerInstant =
                dateTimeProvider.calculateDueTriggerInstant(
                        task.getDueDate(), task.getDueTime(), recipientZone, now);
        if (triggerInstant.isPresent() && triggerInstant.get().isAfter(now.plusSeconds(1))) {
            return new TxOutcome.Reschedule(triggerInstant.get().truncatedTo(ChronoUnit.MICROS));
        }

        String content =
                contentFormatter.formatTaskDueContent(
                        task.getTitle(),
                        task.getGroup().getName(),
                        task.getDueDate(),
                        task.getDueTime(),
                        recipient.getTimeFormat());

        Notification savedNotification =
                notificationRepository.saveAndFlush(
                        new Notification(
                                recipient, NotificationEventType.TASK_DUE, content, false, now));

        WebPushPayload payload =
                new WebPushPayload(
                        savedNotification.getId(),
                        NotificationEventType.TASK_DUE.name(),
                        NotificationContentFormatter.PUSH_NOTIFICATION_TITLE,
                        content,
                        task.getId(),
                        task.getGroup().getId(),
                        now);

        List<SubscriptionSnapshot> snapshots =
                pushSubscriptionRepository.findAllByUserId(recipient.getId()).stream()
                        .map(
                                sub ->
                                        new SubscriptionSnapshot(
                                                sub.getId(),
                                                sub.getEndpoint(),
                                                sub.getP256dh(),
                                                sub.getAuth()))
                        .toList();

        return new TxOutcome.Dispatched(payload, snapshots, now);
    }

    private void dispatchWebPushAndCleanStale(TxOutcome.Dispatched dispatched) {
        if (!vapidConfigurationState.isActive()) {
            log.debug(
                    "Notificación in-app {} persistida; envío Web Push omitido (VAPID inactivo).",
                    dispatched.payload().notificationId());
            return;
        }
        if (dispatched.subscriptions().isEmpty()) {
            log.debug(
                    "Notificación in-app {} persistida; el destinatario no tiene suscripciones"
                            + " push activas.",
                    dispatched.payload().notificationId());
            return;
        }

        List<Long> staleSubscriptionIds = new ArrayList<>();
        for (SubscriptionSnapshot sub : dispatched.subscriptions()) {
            try {
                byte[] encryptedBody =
                        encryptionService.serializeAndEncrypt(
                                dispatched.payload(), sub.p256dh(), sub.auth());
                String vapidHeader =
                        encryptionService.createVapidAuthorizationHeader(
                                sub.endpoint(), vapidConfigurationState, dispatched.now());
                WebPushTransport.DeliveryResult result =
                        webPushTransport.send(sub.endpoint(), encryptedBody, vapidHeader);

                if (result.outcome() == WebPushTransport.DeliveryOutcome.GONE_404_OR_410) {
                    log.info(
                            "Suscripción push id={} reportó HTTP {}; marcada para eliminación"
                                    + " automática (NTF-REQ-17).",
                            sub.id(),
                            result.statusCode());
                    staleSubscriptionIds.add(sub.id());
                } else if (result.outcome() == WebPushTransport.DeliveryOutcome.TRANSIENT_ERROR) {
                    log.warn(
                            "Fallo transitorio al enviar Web Push a suscripción id={} (status={},"
                                    + " detalle={}); se conserva la notificación in-app"
                                    + " (NTF-REQ-18).",
                            sub.id(),
                            result.statusCode(),
                            result.diagnosticMessage());
                }
            } catch (Exception ex) {
                log.warn(
                        "Error al preparar o enviar Web Push para suscripción id={} ({}); se"
                                + " continúa con las demás suscripciones (NTF-REQ-18).",
                        sub.id(),
                        ex.getClass().getSimpleName());
            }
        }

        if (!staleSubscriptionIds.isEmpty()) {
            try {
                transactionOperations.executeWithoutResult(
                        status -> pushSubscriptionRepository.deleteAllByIdIn(staleSubscriptionIds));
            } catch (Exception ex) {
                log.warn(
                        "No se pudieron limpiar suscripciones push expiradas {} ({}).",
                        staleSubscriptionIds,
                        ex.getClass().getSimpleName());
            }
        }
    }

    private static Long parseTaskId(String taskInstanceId) {
        if (taskInstanceId == null
                || !taskInstanceId.startsWith(TaskDueSchedulePort.INSTANCE_PREFIX)) {
            return null;
        }
        String suffix =
                taskInstanceId.substring(TaskDueSchedulePort.INSTANCE_PREFIX.length()).trim();
        if (suffix.isEmpty()) {
            return null;
        }
        try {
            long id = Long.parseLong(suffix);
            return id > 0 ? id : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
