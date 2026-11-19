package com.eyelesscode.task_by_task.task.dto;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/** Jackson 3 deserializer that rejects empty/blank strings for {@link LocalTime} fields. */
public class StrictLocalTimeDeserializer extends ValueDeserializer<LocalTime> {

    @Override
    public LocalTime deserialize(JsonParser p, DeserializationContext ctxt) {
        JsonNode node = ctxt.readTree(p);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            return ctxt.reportInputMismatch(
                    LocalTime.class, "La hora de vencimiento debe tener formato ISO HH:mm[:ss]");
        }
        String text = node.stringValue();
        if (text.isBlank()) {
            return ctxt.reportInputMismatch(
                    LocalTime.class, "La hora de vencimiento no puede estar vacía");
        }
        try {
            return LocalTime.parse(text);
        } catch (DateTimeParseException ex) {
            return ctxt.reportInputMismatch(
                    LocalTime.class, "La hora de vencimiento debe tener formato ISO HH:mm[:ss]");
        }
    }
}
