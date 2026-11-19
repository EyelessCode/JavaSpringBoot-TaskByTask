package com.eyelesscode.task_by_task.shared.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eyelesscode.task_by_task.shared.error.BadRequestException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DateTimeProviderTest {

    private static final ZoneId BOGOTA_ZONE = ZoneId.of("America/Bogota");
    private static final ZoneId MADRID_ZONE = ZoneId.of("Europe/Madrid");
    private static final ZoneId UTC_ZONE = ZoneId.of("UTC");

    private DateTimeProvider createProvider(Instant fixedInstant) {
        return new DateTimeProvider(Clock.fixed(fixedInstant, ZoneOffset.UTC));
    }

    @Nested
    @DisplayName("now() y today()")
    class NowAndTodayTests {

        @Test
        @DisplayName("now() devuelve el instante canónico en UTC")
        void shouldReturnCanonicalUtcInstant() {
            Instant fixed = Instant.parse("2026-11-09T15:30:00.123456Z");
            DateTimeProvider provider = createProvider(fixed);

            assertThat(provider.now()).isEqualTo(fixed);
        }

        @Test
        @DisplayName("today() resuelve la fecha correcta considerando la zona horaria del usuario")
        void shouldResolveCorrectDateInUserTimezone() {
            // 2026-11-10 02:00:00 UTC son las 21:00:00 del 2026-11-09 en Bogotá (UTC-5)
            Instant fixed = Instant.parse("2026-11-10T02:00:00Z");
            DateTimeProvider provider = createProvider(fixed);

            assertThat(provider.today(ZoneOffset.UTC)).isEqualTo(LocalDate.of(2026, 11, 10));
            assertThat(provider.today(BOGOTA_ZONE)).isEqualTo(LocalDate.of(2026, 11, 9));
        }
    }

    @Nested
    @DisplayName("parseZoneId()")
    class ParseZoneIdTests {

        private final DateTimeProvider provider =
                new DateTimeProvider(Clock.system(ZoneOffset.UTC));

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t\n"})
        @DisplayName("Retorna ZoneOffset.UTC cuando el valor es nulo o en blanco (FND-REQ-27)")
        void shouldReturnUtcForNullOrBlank(String input) {
            assertThat(provider.parseZoneId(input)).isEqualTo(UTC_ZONE);
        }

        @Test
        @DisplayName("Resuelve identificadores IANA válidos correctamente")
        void shouldParseValidIanaZoneIds() {
            assertThat(provider.parseZoneId("America/Bogota")).isEqualTo(BOGOTA_ZONE);
            assertThat(provider.parseZoneId("Europe/Madrid")).isEqualTo(MADRID_ZONE);
            assertThat(provider.parseZoneId("UTC")).isEqualTo(UTC_ZONE);
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "Invalida/Zona",
                    "Bogota",
                    "GMT+99",
                    "America/Ciudad_Que_No_Existe",
                    "12345"
                })
        @DisplayName("Lanza BadRequestException en español ante zonas no reconocidas (EC-06)")
        void shouldThrowBadRequestForInvalidTimezones(String invalidZone) {
            assertThatThrownBy(() -> provider.parseZoneId(invalidZone))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("La zona horaria proporcionada")
                    .hasMessageContaining("no es válida");
        }
    }

    @Nested
    @DisplayName("validateDueDateAndTime()")
    class ValidateDueDateAndTimeTests {

        private final DateTimeProvider provider =
                new DateTimeProvider(Clock.system(ZoneOffset.UTC));

        @Test
        @DisplayName(
                "Lanza BadRequestException si se provee dueTime sin dueDate (FND-REQ-03, EC-07)")
        void shouldThrowWhenDueTimeWithoutDueDate() {
            assertThatThrownBy(() -> provider.validateDueDateAndTime(null, LocalTime.of(14, 0)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage(
                            "La hora de vencimiento requiere especificar una fecha de"
                                    + " vencimiento.");
        }

        @Test
        @DisplayName("Permite combinaciones válidas sin lanzar excepción")
        void shouldAllowValidDateCombinations() {
            assertThatCode(
                            () ->
                                    provider.validateDueDateAndTime(
                                            LocalDate.of(2026, 12, 1), LocalTime.of(10, 0)))
                    .doesNotThrowAnyException();

            assertThatCode(() -> provider.validateDueDateAndTime(LocalDate.of(2026, 12, 1), null))
                    .doesNotThrowAnyException();

            assertThatCode(() -> provider.validateDueDateAndTime(null, null))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("isOverdue() — Cálculo de estado vencido (FND-REQ-29, RF-14)")
    class IsOverdueTests {

        @Test
        @DisplayName("Retorna false si la tarea no tiene fecha de vencimiento")
        void shouldReturnFalseWhenNoDueDate() {
            DateTimeProvider provider = createProvider(Instant.now());
            assertThat(provider.isOverdue(null, null, BOGOTA_ZONE)).isFalse();
        }

        @Test
        @DisplayName(
                "Tareas con hora específica vencen inmediatamente después de ese instante exacto")
        void shouldEvaluateOverdueWithExactTime() {
            LocalDate dueDate = LocalDate.of(2026, 11, 9);
            LocalTime dueTime = LocalTime.of(15, 0, 0); // 15:00:00 en Bogotá

            Instant dueInstant = ZonedDateTime.of(dueDate, dueTime, BOGOTA_ZONE).toInstant();
            Instant before = dueInstant.minusMillis(1);
            Instant exact = dueInstant;
            Instant after = dueInstant.plusMillis(1);

            DateTimeProvider provider = createProvider(dueInstant);

            assertThat(provider.isOverdue(dueDate, dueTime, BOGOTA_ZONE, before)).isFalse();
            assertThat(provider.isOverdue(dueDate, dueTime, BOGOTA_ZONE, exact)).isFalse();
            assertThat(provider.isOverdue(dueDate, dueTime, BOGOTA_ZONE, after)).isTrue();
        }

        @Test
        @DisplayName(
                "Tareas de solo fecha vencen únicamente después de las 23:59:59.999 locales"
                        + " (RF-14)")
        void shouldEvaluateOverdueForDateOnlyTaskAtEndOfDay() {
            LocalDate dueDate = LocalDate.of(2026, 11, 9);

            // Instantes en Bogotá: 09:00 AM, 15:00 PM, 23:59:59.999, y 1 milisegundo después
            // (23:59:60 / 00:00 día siguiente)
            Instant nineAm =
                    ZonedDateTime.of(dueDate, LocalTime.of(9, 0, 0), BOGOTA_ZONE).toInstant();
            Instant threePm =
                    ZonedDateTime.of(dueDate, LocalTime.of(15, 0, 0), BOGOTA_ZONE).toInstant();
            Instant endOfDay =
                    ZonedDateTime.of(dueDate, LocalTime.of(23, 59, 59, 999_000_000), BOGOTA_ZONE)
                            .toInstant();
            Instant afterEndOfDay = endOfDay.plusMillis(1);

            DateTimeProvider provider = createProvider(endOfDay);

            // Sigue sin estar vencida durante todo el día hasta 23:59:59.999
            assertThat(provider.isOverdue(dueDate, null, BOGOTA_ZONE, nineAm)).isFalse();
            assertThat(provider.isOverdue(dueDate, null, BOGOTA_ZONE, threePm)).isFalse();
            assertThat(provider.isOverdue(dueDate, null, BOGOTA_ZONE, endOfDay)).isFalse();

            // Pasa a vencida justo después del fin del día local
            assertThat(provider.isOverdue(dueDate, null, BOGOTA_ZONE, afterEndOfDay)).isTrue();
        }
    }

    @Nested
    @DisplayName(
            "calculateDueTriggerInstant() — Regla de las 09:00 AM y alertas (FND-REQ-30, RF-28,"
                    + " EC-01)")
    class CalculateDueTriggerInstantTests {

        @Test
        @DisplayName("Retorna Optional.empty() cuando la tarea no tiene fecha")
        void shouldReturnEmptyWhenNoDueDate() {
            DateTimeProvider provider = createProvider(Instant.now());
            assertThat(provider.calculateDueTriggerInstant(null, null, BOGOTA_ZONE)).isEmpty();
        }

        @Test
        @DisplayName(
                "Retorna Optional.empty() para tareas que nacen vencidas (fecha u hora pasada,"
                        + " EC-01)")
        void shouldReturnEmptyForTasksBornOverdue() {
            LocalDate yesterday = LocalDate.of(2026, 11, 8);
            Instant now =
                    ZonedDateTime.of(LocalDate.of(2026, 11, 9), LocalTime.of(12, 0, 0), BOGOTA_ZONE)
                            .toInstant();
            DateTimeProvider provider = createProvider(now);

            // Fecha pasada: no programa alertas
            assertThat(provider.calculateDueTriggerInstant(yesterday, null, BOGOTA_ZONE, now))
                    .isEmpty();

            // Fecha de hoy pero hora ya pasada: no programa alertas
            LocalDate today = LocalDate.of(2026, 11, 9);
            LocalTime pastTime = LocalTime.of(10, 0, 0);
            assertThat(provider.calculateDueTriggerInstant(today, pastTime, BOGOTA_ZONE, now))
                    .isEmpty();
        }

        @Test
        @DisplayName("Tareas con hora específica futura disparan en el instante exacto programado")
        void shouldTriggerAtExactTimeForFutureTaskWithTime() {
            LocalDate today = LocalDate.of(2026, 11, 9);
            LocalTime futureTime = LocalTime.of(16, 0, 0);

            Instant now = ZonedDateTime.of(today, LocalTime.of(12, 0, 0), BOGOTA_ZONE).toInstant();
            Instant expectedTrigger = ZonedDateTime.of(today, futureTime, BOGOTA_ZONE).toInstant();

            DateTimeProvider provider = createProvider(now);

            Optional<Instant> trigger =
                    provider.calculateDueTriggerInstant(today, futureTime, BOGOTA_ZONE, now);
            assertThat(trigger).contains(expectedTrigger);
        }

        @Test
        @DisplayName(
                "Tareas de solo fecha para días futuros disparan a las 09:00:00 AM locales"
                        + " (FND-REQ-30)")
        void shouldTriggerAtNineAmForFutureDateOnlyTask() {
            LocalDate tomorrow = LocalDate.of(2026, 11, 10);
            Instant now =
                    ZonedDateTime.of(LocalDate.of(2026, 11, 9), LocalTime.of(14, 0, 0), BOGOTA_ZONE)
                            .toInstant();
            Instant expectedNineAmTomorrow =
                    ZonedDateTime.of(tomorrow, LocalTime.of(9, 0, 0), BOGOTA_ZONE).toInstant();

            DateTimeProvider provider = createProvider(now);

            Optional<Instant> trigger =
                    provider.calculateDueTriggerInstant(tomorrow, null, BOGOTA_ZONE, now);
            assertThat(trigger).contains(expectedNineAmTomorrow);
        }

        @Test
        @DisplayName(
                "Tareas de solo fecha para 'hoy' creadas antes de las 09:00 AM disparan a las"
                        + " 09:00:00 AM locales")
        void shouldTriggerAtNineAmWhenCreatedTodayBeforeNineAm() {
            LocalDate today = LocalDate.of(2026, 11, 9);
            Instant nowSevenAm =
                    ZonedDateTime.of(today, LocalTime.of(7, 30, 0), BOGOTA_ZONE).toInstant();
            Instant expectedNineAm =
                    ZonedDateTime.of(today, LocalTime.of(9, 0, 0), BOGOTA_ZONE).toInstant();

            DateTimeProvider provider = createProvider(nowSevenAm);

            Optional<Instant> trigger =
                    provider.calculateDueTriggerInstant(today, null, BOGOTA_ZONE, nowSevenAm);
            assertThat(trigger).contains(expectedNineAm);
        }

        @Test
        @DisplayName(
                "Tareas de solo fecha para 'hoy' creadas o editadas después de las 09:00 AM"
                        + " disparan de inmediato (FND-REQ-30, EC-01, RF-28)")
        void shouldTriggerImmediatelyWhenCreatedTodayAfterNineAm() {
            LocalDate today = LocalDate.of(2026, 11, 9);
            Instant nowTwoThirtyPm =
                    ZonedDateTime.of(today, LocalTime.of(14, 30, 0), BOGOTA_ZONE).toInstant();

            DateTimeProvider provider = createProvider(nowTwoThirtyPm);

            // Debe disparar de inmediato (Optional.of(nowTwoThirtyPm))
            Optional<Instant> trigger =
                    provider.calculateDueTriggerInstant(today, null, BOGOTA_ZONE, nowTwoThirtyPm);
            assertThat(trigger).contains(nowTwoThirtyPm);

            // Y la tarea NO debe estar vencida aún (permanece pendiente hasta 23:59:59.999)
            assertThat(provider.isOverdue(today, null, BOGOTA_ZONE, nowTwoThirtyPm)).isFalse();
        }

        @Test
        @DisplayName(
                "Tareas de solo fecha para 'hoy' exactamente a las 09:00:00 AM disparan de"
                        + " inmediato")
        void shouldTriggerImmediatelyWhenCreatedExactlyAtNineAm() {
            LocalDate today = LocalDate.of(2026, 11, 9);
            Instant exactNineAm =
                    ZonedDateTime.of(today, LocalTime.of(9, 0, 0), BOGOTA_ZONE).toInstant();

            DateTimeProvider provider = createProvider(exactNineAm);

            Optional<Instant> trigger =
                    provider.calculateDueTriggerInstant(today, null, BOGOTA_ZONE, exactNineAm);
            assertThat(trigger).contains(exactNineAm);
        }
    }
}
