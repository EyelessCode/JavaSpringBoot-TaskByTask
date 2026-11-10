package com.eyelesscode.task_by_task.shared.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eyelesscode.task_by_task.shared.error.BadRequestException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class KeysetCursorCodecTest {

    private final KeysetCursorCodec codec = new KeysetCursorCodec();

    private static final Instant SAMPLE_INSTANT = Instant.parse("2026-11-09T18:00:00Z");

    @Nested
    @DisplayName("Round-trip: Codificación y decodificación exacta")
    class RoundTripTests {

        @Test
        @DisplayName("TaskCursor completo con fecha y hora")
        void shouldRoundTripCompleteTaskCursor() {
            var cursor =
                    new KeysetCursor.TaskCursor(
                            false,
                            LocalDate.of(2026, 12, 1),
                            LocalTime.of(14, 30),
                            SAMPLE_INSTANT,
                            101L);

            String token = codec.encode(cursor);
            KeysetCursor.TaskCursor decoded = codec.decodeTaskCursor(token);

            assertThat(decoded).isEqualTo(cursor);
            assertThat(decoded.completed()).isFalse();
            assertThat(decoded.dueDate()).isEqualTo(LocalDate.of(2026, 12, 1));
            assertThat(decoded.dueTime()).isEqualTo(LocalTime.of(14, 30));
            assertThat(decoded.createdAt()).isEqualTo(SAMPLE_INSTANT);
            assertThat(decoded.id()).isEqualTo(101L);
        }

        @Test
        @DisplayName("TaskCursor de solo fecha (dueTime == null)")
        void shouldRoundTripDateOnlyTaskCursor() {
            var cursor =
                    new KeysetCursor.TaskCursor(
                            true, LocalDate.of(2026, 12, 2), null, SAMPLE_INSTANT, 102L);

            String token = codec.encode(cursor);
            KeysetCursor.TaskCursor decoded = codec.decodeTaskCursor(token);

            assertThat(decoded).isEqualTo(cursor);
            assertThat(decoded.dueTime()).isNull();
        }

        @Test
        @DisplayName("TaskCursor sin fecha ni hora (dueDate == null && dueTime == null)")
        void shouldRoundTripTaskCursorWithoutDates() {
            var cursor = new KeysetCursor.TaskCursor(false, null, null, SAMPLE_INSTANT, 103L);

            String token = codec.encode(cursor);
            KeysetCursor.TaskCursor decoded = codec.decodeTaskCursor(token);

            assertThat(decoded).isEqualTo(cursor);
            assertThat(decoded.dueDate()).isNull();
            assertThat(decoded.dueTime()).isNull();
        }

        @Test
        @DisplayName("GroupCursor (personal y no personal)")
        void shouldRoundTripGroupCursor() {
            var personalCursor = new KeysetCursor.GroupCursor(true, SAMPLE_INSTANT, 1L);
            String tokenPersonal = codec.encode(personalCursor);
            assertThat(codec.decodeGroupCursor(tokenPersonal)).isEqualTo(personalCursor);

            var sharedCursor = new KeysetCursor.GroupCursor(false, SAMPLE_INSTANT, 2L);
            String tokenShared = codec.encode(sharedCursor);
            assertThat(codec.decodeGroupCursor(tokenShared)).isEqualTo(sharedCursor);
        }

        @Test
        @DisplayName("TagCursor (nombre y id)")
        void shouldRoundTripTagCursor() {
            var cursor = new KeysetCursor.TagCursor("Trabajo", 42L);

            String token = codec.encode(cursor);
            KeysetCursor.TagCursor decoded = codec.decodeTagCursor(token);

            assertThat(decoded).isEqualTo(cursor);
            assertThat(decoded.name()).isEqualTo("Trabajo");
            assertThat(decoded.id()).isEqualTo(42L);
        }

        @Test
        @DisplayName("NotificationCursor (createdAt y id)")
        void shouldRoundTripNotificationCursor() {
            var cursor = new KeysetCursor.NotificationCursor(SAMPLE_INSTANT, 88L);

            String token = codec.encode(cursor);
            KeysetCursor.NotificationCursor decoded = codec.decodeNotificationCursor(token);

            assertThat(decoded).isEqualTo(cursor);
            assertThat(decoded.createdAt()).isEqualTo(SAMPLE_INSTANT);
            assertThat(decoded.id()).isEqualTo(88L);
        }
    }

    @Nested
    @DisplayName("Formato seguro para URLs (URL-safe)")
    class UrlSafeFormatTests {

        @Test
        @DisplayName(
                "El token generado no contiene padding '=' ni caracteres problemáticos '+', '/'")
        void shouldProduceUrlSafeBase64Tokens() {
            var cursor =
                    new KeysetCursor.TaskCursor(
                            true,
                            LocalDate.of(2026, 12, 1),
                            LocalTime.of(10, 0),
                            SAMPLE_INSTANT,
                            99999L);

            String token = codec.encode(cursor);

            assertThat(token).doesNotContain("=");
            assertThat(token).doesNotContain("+");
            assertThat(token).doesNotContain("/");
            assertThat(token).matches("^[A-Za-z0-9_-]+$");
        }
    }

    @Nested
    @DisplayName("Rechazo de tokens manipulados, corruptos o inválidos")
    class TamperingAndValidationTests {

        private static final String EXPECTED_ERROR =
                "El cursor de paginación proporcionado no es válido";

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t\n"})
        @DisplayName("Rechaza tokens nulos o en blanco")
        void shouldRejectNullOrBlankToken(String token) {
            assertThatThrownBy(() -> codec.decode(token))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage(EXPECTED_ERROR);
        }

        @Test
        @DisplayName("Rechaza tokens con bytes alterados (violación de checksum/tampering)")
        void shouldRejectTamperedToken() {
            var cursor = new KeysetCursor.TagCursor("Urgente", 10L);
            String validToken = codec.encode(cursor);

            // Modificar un carácter del token para simular manipulación
            char[] chars = validToken.toCharArray();
            chars[chars.length - 2] = (chars[chars.length - 2] == 'a') ? 'b' : 'a';
            String tamperedToken = new String(chars);

            assertThatThrownBy(() -> codec.decode(tamperedToken))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage(EXPECTED_ERROR);
        }

        @Test
        @DisplayName("Rechaza tokens truncados")
        void shouldRejectTruncatedToken() {
            var cursor = new KeysetCursor.GroupCursor(true, SAMPLE_INSTANT, 1L);
            String validToken = codec.encode(cursor);

            String truncated = validToken.substring(0, validToken.length() / 2);

            assertThatThrownBy(() -> codec.decode(truncated))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage(EXPECTED_ERROR);
        }

        @Test
        @DisplayName("Rechaza tokens con caracteres no válidos de Base64")
        void shouldRejectInvalidBase64Characters() {
            assertThatThrownBy(() -> codec.decode("not@valid#base64!"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage(EXPECTED_ERROR);
        }

        @Test
        @DisplayName("Rechaza decodificación cuando el tipo de cursor no coincide con el esperado")
        void shouldRejectTypeMismatch() {
            // Se genera un token de Tag
            String tagToken = codec.encode(new KeysetCursor.TagCursor("Urgente", 1L));

            // Intentar decodificar como Task debe fallar
            assertThatThrownBy(() -> codec.decodeTaskCursor(tagToken))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage(EXPECTED_ERROR);

            // Intentar decodificar como Group debe fallar
            assertThatThrownBy(() -> codec.decodeGroupCursor(tagToken))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage(EXPECTED_ERROR);

            // Intentar decodificar como Notification debe fallar
            assertThatThrownBy(() -> codec.decodeNotificationCursor(tagToken))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage(EXPECTED_ERROR);
        }
    }

    @Nested
    @DisplayName("Comportamiento de KeysetPage<T>")
    class KeysetPageTests {

        @Test
        @DisplayName("KeysetPage garantiza inmutabilidad de content con List.copyOf")
        void shouldEnforceImmutabilityOfContent() {
            List<String> mutableList = new ArrayList<>(List.of("item1", "item2"));
            var page = new KeysetPage<>(mutableList, "cursor123", true, 2);

            assertThatThrownBy(() -> page.content().add("item3"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("KeysetPage.of divide correctamente cuando items supera el límite solicitado")
        void shouldSliceItemsWhenExceedingLimit() {
            List<String> rawItems = List.of("tarea1", "tarea2", "tarea3"); // 3 items para limit 2
            int limit = 2;
            String nextCursor = "token_de_tarea2";

            KeysetPage<String> page = KeysetPage.of(rawItems, limit, nextCursor);

            assertThat(page.hasNext()).isTrue();
            assertThat(page.content()).containsExactly("tarea1", "tarea2");
            assertThat(page.nextCursor()).isEqualTo("token_de_tarea2");
            assertThat(page.size()).isEqualTo(2);
        }

        @Test
        @DisplayName(
                "KeysetPage.of indica hasNext=false y nextCursor=null cuando no supera el límite")
        void shouldHandleLastPageCorrectly() {
            List<String> rawItems = List.of("tarea1", "tarea2"); // 2 items para limit 2
            int limit = 2;
            String nextCursor = "token_ignorado";

            KeysetPage<String> page = KeysetPage.of(rawItems, limit, nextCursor);

            assertThat(page.hasNext()).isFalse();
            assertThat(page.content()).containsExactly("tarea1", "tarea2");
            assertThat(page.nextCursor()).isNull();
            assertThat(page.size()).isEqualTo(2);
        }
    }
}
