package com.eyelesscode.task_by_task.task.dto;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/** Jackson 3 deserializer that rejects empty/blank strings for {@link LocalDate} fields. */
public class StrictLocalDateDeserializer extends ValueDeserializer<LocalDate> {

    @Override
    public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) {
        JsonNode node = ctxt.readTree(p);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            return ctxt.reportInputMismatch(
                    LocalDate.class, "La fecha de vencimiento debe tener formato ISO yyyy-MM-dd");
        }
        String text = node.stringValue();
        if (text.isBlank()) {
            return ctxt.reportInputMismatch(
                    LocalDate.class, "La fecha de vencimiento no puede estar vacía");
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException ex) {
            return ctxt.reportInputMismatch(
                    LocalDate.class, "La fecha de vencimiento debe tener formato ISO yyyy-MM-dd");
        }
    }
}
