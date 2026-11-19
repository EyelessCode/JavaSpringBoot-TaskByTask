package com.eyelesscode.task_by_task.shared.pagination;

import com.eyelesscode.task_by_task.shared.error.BadRequestException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Base64;
import java.util.Objects;
import java.util.zip.CRC32;
import org.springframework.stereotype.Component;

@Component
public class KeysetCursorCodec {

    private static final String INVALID_CURSOR_MSG =
            "El cursor de paginación proporcionado no es válido";

    private final ObjectMapper objectMapper;

    public KeysetCursorCodec() {
        this(createDefaultObjectMapper());
    }

    public KeysetCursorCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    private static ObjectMapper createDefaultObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CursorPayload(
            String type,
            Boolean completed,
            LocalDate dueDate,
            LocalTime dueTime,
            Boolean isPersonal,
            String name,
            Instant createdAt,
            Long id) {}

    public String encode(KeysetCursor cursor) {
        Objects.requireNonNull(cursor, "cursor must not be null");
        CursorPayload payload =
                switch (cursor) {
                    case KeysetCursor.TaskCursor t ->
                            new CursorPayload(
                                    "TASK",
                                    t.completed(),
                                    t.dueDate(),
                                    t.dueTime(),
                                    null,
                                    null,
                                    t.createdAt(),
                                    t.id());
                    case KeysetCursor.GroupCursor g ->
                            new CursorPayload(
                                    "GROUP",
                                    null,
                                    null,
                                    null,
                                    g.isPersonal(),
                                    null,
                                    g.createdAt(),
                                    g.id());
                    case KeysetCursor.TagCursor tag ->
                            new CursorPayload(
                                    "TAG", null, null, null, null, tag.name(), null, tag.id());
                    case KeysetCursor.NotificationCursor n ->
                            new CursorPayload(
                                    "NOTIFICATION",
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    n.createdAt(),
                                    n.id());
                };

        try {
            byte[] jsonBytes = objectMapper.writeValueAsBytes(payload);
            long checksum = computeChecksum(jsonBytes);

            ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES + jsonBytes.length);
            buffer.putLong(checksum);
            buffer.put(jsonBytes);

            return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Error al serializar el cursor de paginación", ex);
        }
    }

    public String encodeTaskCursor(
            Boolean completed, LocalDate dueDate, LocalTime dueTime, Instant createdAt, Long id) {
        return encode(new KeysetCursor.TaskCursor(completed, dueDate, dueTime, createdAt, id));
    }

    public String encodeGroupCursor(Boolean isPersonal, Instant createdAt, Long id) {
        return encode(new KeysetCursor.GroupCursor(isPersonal, createdAt, id));
    }

    public String encodeTagCursor(String name, Long id) {
        return encode(new KeysetCursor.TagCursor(name, id));
    }

    public String encodeNotificationCursor(Instant createdAt, Long id) {
        return encode(new KeysetCursor.NotificationCursor(createdAt, id));
    }

    public KeysetCursor decode(String token) {
        if (token == null || token.isBlank()) {
            throw new BadRequestException(INVALID_CURSOR_MSG);
        }

        byte[] rawBytes;
        try {
            rawBytes = Base64.getUrlDecoder().decode(token.trim());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(INVALID_CURSOR_MSG, ex);
        }

        if (rawBytes.length <= Long.BYTES) {
            throw new BadRequestException(INVALID_CURSOR_MSG);
        }

        ByteBuffer buffer = ByteBuffer.wrap(rawBytes);
        long expectedChecksum = buffer.getLong();
        byte[] jsonBytes = new byte[rawBytes.length - Long.BYTES];
        buffer.get(jsonBytes);

        long actualChecksum = computeChecksum(jsonBytes);
        if (expectedChecksum != actualChecksum) {
            throw new BadRequestException(INVALID_CURSOR_MSG);
        }

        CursorPayload payload;
        try {
            payload = objectMapper.readValue(jsonBytes, CursorPayload.class);
        } catch (Exception ex) {
            throw new BadRequestException(INVALID_CURSOR_MSG, ex);
        }

        if (payload == null || payload.type() == null || payload.id() == null) {
            throw new BadRequestException(INVALID_CURSOR_MSG);
        }

        try {
            return switch (payload.type()) {
                case "TASK" -> {
                    if (payload.completed() == null
                            || payload.createdAt() == null
                            || (payload.dueTime() != null && payload.dueDate() == null)) {
                        throw new BadRequestException(INVALID_CURSOR_MSG);
                    }
                    yield new KeysetCursor.TaskCursor(
                            payload.completed(),
                            payload.dueDate(),
                            payload.dueTime(),
                            payload.createdAt(),
                            payload.id());
                }
                case "GROUP" -> {
                    if (payload.isPersonal() == null || payload.createdAt() == null) {
                        throw new BadRequestException(INVALID_CURSOR_MSG);
                    }
                    yield new KeysetCursor.GroupCursor(
                            payload.isPersonal(), payload.createdAt(), payload.id());
                }
                case "TAG" -> {
                    if (payload.name() == null || payload.name().isBlank()) {
                        throw new BadRequestException(INVALID_CURSOR_MSG);
                    }
                    yield new KeysetCursor.TagCursor(payload.name(), payload.id());
                }
                case "NOTIFICATION" -> {
                    if (payload.createdAt() == null || payload.id() == null) {
                        throw new BadRequestException(INVALID_CURSOR_MSG);
                    }
                    yield new KeysetCursor.NotificationCursor(payload.createdAt(), payload.id());
                }
                default -> throw new BadRequestException(INVALID_CURSOR_MSG);
            };
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(INVALID_CURSOR_MSG, ex);
        }
    }

    public KeysetCursor.TaskCursor decodeTaskCursor(String token) {
        KeysetCursor cursor = decode(token);
        if (cursor instanceof KeysetCursor.TaskCursor taskCursor) {
            return taskCursor;
        }
        throw new BadRequestException(INVALID_CURSOR_MSG);
    }

    public KeysetCursor.GroupCursor decodeGroupCursor(String token) {
        KeysetCursor cursor = decode(token);
        if (cursor instanceof KeysetCursor.GroupCursor groupCursor) {
            return groupCursor;
        }
        throw new BadRequestException(INVALID_CURSOR_MSG);
    }

    public KeysetCursor.TagCursor decodeTagCursor(String token) {
        KeysetCursor cursor = decode(token);
        if (cursor instanceof KeysetCursor.TagCursor tagCursor) {
            return tagCursor;
        }
        throw new BadRequestException(INVALID_CURSOR_MSG);
    }

    public KeysetCursor.NotificationCursor decodeNotificationCursor(String token) {
        KeysetCursor cursor = decode(token);
        if (cursor instanceof KeysetCursor.NotificationCursor notificationCursor) {
            return notificationCursor;
        }
        throw new BadRequestException(INVALID_CURSOR_MSG);
    }

    private static long computeChecksum(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }
}
