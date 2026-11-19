package com.eyelesscode.task_by_task.shared.time;

import com.eyelesscode.task_by_task.shared.error.BadRequestException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class DateTimeProvider {

    private static final LocalTime NINE_AM = LocalTime.of(9, 0, 0);
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59, 999_000_000);

    private final Clock clock;

    public DateTimeProvider(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    public Instant now() {
        return Instant.now(clock);
    }

    public LocalDate today(ZoneId zoneId) {
        Objects.requireNonNull(zoneId, "ZoneId must not be null");
        return LocalDate.ofInstant(now(), zoneId);
    }

    public ZoneId parseZoneId(String zoneIdStr) {
        if (zoneIdStr == null || zoneIdStr.isBlank()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(zoneIdStr.trim());
        } catch (DateTimeException ex) {
            throw new BadRequestException(
                    "La zona horaria proporcionada '"
                            + zoneIdStr.trim()
                            + "' no es válida. Debe ser un identificador IANA reconocido (ej."
                            + " 'America/Bogota', 'UTC').",
                    ex);
        }
    }

    public void validateDueDateAndTime(LocalDate dueDate, LocalTime dueTime) {
        if (dueTime != null && dueDate == null) {
            throw new BadRequestException(
                    "La hora de vencimiento requiere especificar una fecha de vencimiento.");
        }
    }

    public boolean isOverdue(
            LocalDate dueDate, LocalTime dueTime, ZoneId userZone, Instant currentInstant) {
        if (dueDate == null) {
            return false;
        }
        Objects.requireNonNull(userZone, "userZone must not be null");
        Objects.requireNonNull(currentInstant, "currentInstant must not be null");

        if (dueTime != null) {
            Instant dueInstant = ZonedDateTime.of(dueDate, dueTime, userZone).toInstant();
            return currentInstant.isAfter(dueInstant);
        } else {
            Instant endOfDayInstant = ZonedDateTime.of(dueDate, END_OF_DAY, userZone).toInstant();
            return currentInstant.isAfter(endOfDayInstant);
        }
    }

    public boolean isOverdue(LocalDate dueDate, LocalTime dueTime, ZoneId userZone) {
        return isOverdue(dueDate, dueTime, userZone, now());
    }

    public Optional<Instant> calculateDueTriggerInstant(
            LocalDate dueDate, LocalTime dueTime, ZoneId userZone, Instant currentInstant) {
        if (dueDate == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(userZone, "userZone must not be null");
        Objects.requireNonNull(currentInstant, "currentInstant must not be null");

        if (isOverdue(dueDate, dueTime, userZone, currentInstant)) {
            return Optional.empty();
        }

        if (dueTime != null) {
            return Optional.of(ZonedDateTime.of(dueDate, dueTime, userZone).toInstant());
        }

        Instant nineAmInstant = ZonedDateTime.of(dueDate, NINE_AM, userZone).toInstant();
        LocalDate todayInZone = LocalDate.ofInstant(currentInstant, userZone);

        if (dueDate.equals(todayInZone) && !currentInstant.isBefore(nineAmInstant)) {
            return Optional.of(currentInstant);
        }

        return Optional.of(nineAmInstant);
    }

    public Optional<Instant> calculateDueTriggerInstant(
            LocalDate dueDate, LocalTime dueTime, ZoneId userZone) {
        return calculateDueTriggerInstant(dueDate, dueTime, userZone, now());
    }
}
