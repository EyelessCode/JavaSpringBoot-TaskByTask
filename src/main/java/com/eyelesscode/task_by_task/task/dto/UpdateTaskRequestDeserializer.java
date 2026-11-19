package com.eyelesscode.task_by_task.task.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/** Jackson 3 deserializer for {@link UpdateTaskRequest} preserving omitted vs null semantics. */
public class UpdateTaskRequestDeserializer extends ValueDeserializer<UpdateTaskRequest> {

    @Override
    public UpdateTaskRequest deserialize(JsonParser p, DeserializationContext ctxt) {
        JsonNode root = ctxt.readTree(p);
        if (root == null || !root.isObject()) {
            return ctxt.reportInputMismatch(
                    UpdateTaskRequest.class, "El cuerpo de la solicitud debe ser un objeto JSON");
        }

        PatchField<String> title = readTitle(root.get("title"), ctxt);
        PatchField<String> description = readDescription(root.get("description"), ctxt);
        PatchField<LocalDate> dueDate = readDueDate(root.get("dueDate"), ctxt);
        PatchField<LocalTime> dueTime = readDueTime(root.get("dueTime"), ctxt);
        PatchField<List<String>> tags = readTags(root.get("tags"), ctxt);

        return new UpdateTaskRequest(title, description, dueDate, dueTime, tags);
    }

    private static PatchField<String> readTitle(JsonNode node, DeserializationContext ctxt) {
        if (node == null) {
            return PatchField.omitted();
        }
        if (node.isNull()) {
            return PatchField.of(null);
        }
        if (!node.isString()) {
            return ctxt.reportInputMismatch(
                    UpdateTaskRequest.class, "El título debe ser una cadena de texto");
        }
        return PatchField.of(node.stringValue().trim());
    }

    private static PatchField<String> readDescription(JsonNode node, DeserializationContext ctxt) {
        if (node == null) {
            return PatchField.omitted();
        }
        if (node.isNull()) {
            return PatchField.of(null);
        }
        if (!node.isString()) {
            return ctxt.reportInputMismatch(
                    UpdateTaskRequest.class, "La descripción debe ser una cadena de texto");
        }
        String trimmed = node.stringValue().trim();
        return PatchField.of(trimmed.isEmpty() ? null : trimmed);
    }

    private static PatchField<LocalDate> readDueDate(JsonNode node, DeserializationContext ctxt) {
        if (node == null) {
            return PatchField.omitted();
        }
        if (node.isNull()) {
            return PatchField.of(null);
        }
        if (!node.isString()) {
            return ctxt.reportInputMismatch(
                    UpdateTaskRequest.class,
                    "La fecha de vencimiento debe tener formato ISO yyyy-MM-dd");
        }
        String text = node.stringValue();
        if (text.isBlank()) {
            return ctxt.reportInputMismatch(
                    UpdateTaskRequest.class, "La fecha de vencimiento no puede estar vacía");
        }
        try {
            return PatchField.of(LocalDate.parse(text));
        } catch (DateTimeParseException ex) {
            return ctxt.reportInputMismatch(
                    UpdateTaskRequest.class,
                    "La fecha de vencimiento debe tener formato ISO yyyy-MM-dd");
        }
    }

    private static PatchField<LocalTime> readDueTime(JsonNode node, DeserializationContext ctxt) {
        if (node == null) {
            return PatchField.omitted();
        }
        if (node.isNull()) {
            return PatchField.of(null);
        }
        if (!node.isString()) {
            return ctxt.reportInputMismatch(
                    UpdateTaskRequest.class,
                    "La hora de vencimiento debe tener formato ISO HH:mm[:ss]");
        }
        String text = node.stringValue();
        if (text.isBlank()) {
            return ctxt.reportInputMismatch(
                    UpdateTaskRequest.class, "La hora de vencimiento no puede estar vacía");
        }
        try {
            return PatchField.of(LocalTime.parse(text));
        } catch (DateTimeParseException ex) {
            return ctxt.reportInputMismatch(
                    UpdateTaskRequest.class,
                    "La hora de vencimiento debe tener formato ISO HH:mm[:ss]");
        }
    }

    private static PatchField<List<String>> readTags(JsonNode node, DeserializationContext ctxt) {
        if (node == null) {
            return PatchField.omitted();
        }
        if (node.isNull()) {
            return PatchField.of(null);
        }
        if (!node.isArray()) {
            return ctxt.reportInputMismatch(
                    UpdateTaskRequest.class, "Las etiquetas deben enviarse como una lista");
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode elem : node) {
            if (elem == null || elem.isNull()) {
                values.add(null);
            } else if (elem.isString()) {
                values.add(elem.stringValue().trim());
            } else {
                return ctxt.reportInputMismatch(
                        UpdateTaskRequest.class, "Cada etiqueta debe ser una cadena de texto");
            }
        }
        return PatchField.of(Collections.unmodifiableList(values));
    }
}
