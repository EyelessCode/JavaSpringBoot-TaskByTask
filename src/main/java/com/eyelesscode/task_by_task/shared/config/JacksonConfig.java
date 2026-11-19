package com.eyelesscode.task_by_task.shared.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * Configures Spring Boot 4's Jackson 3 {@code JsonMapper} to ignore unknown JSON properties
     * uniformly while rejecting incompatible scalar coercions (HRD-REQ-07).
     */
    @Bean
    public JsonMapperBuilderCustomizer strictJsonMapperCustomizer() {
        return builder ->
                builder.disable(
                                tools.jackson.databind.DeserializationFeature
                                        .FAIL_ON_UNKNOWN_PROPERTIES)
                        .withCoercionConfig(
                                LogicalType.Textual,
                                cfg ->
                                        cfg.setCoercion(
                                                        CoercionInputShape.Integer,
                                                        CoercionAction.Fail)
                                                .setCoercion(
                                                        CoercionInputShape.Float,
                                                        CoercionAction.Fail)
                                                .setCoercion(
                                                        CoercionInputShape.Boolean,
                                                        CoercionAction.Fail))
                        .withCoercionConfig(
                                LogicalType.Boolean,
                                cfg ->
                                        cfg.setCoercion(
                                                        CoercionInputShape.Integer,
                                                        CoercionAction.Fail)
                                                .setCoercion(
                                                        CoercionInputShape.Float,
                                                        CoercionAction.Fail)
                                                .setCoercion(
                                                        CoercionInputShape.String,
                                                        CoercionAction.Fail)
                                                .setCoercion(
                                                        CoercionInputShape.EmptyString,
                                                        CoercionAction.Fail))
                        .withCoercionConfig(
                                LogicalType.Integer,
                                cfg ->
                                        cfg.setCoercion(
                                                        CoercionInputShape.String,
                                                        CoercionAction.Fail)
                                                .setCoercion(
                                                        CoercionInputShape.Float,
                                                        CoercionAction.Fail)
                                                .setCoercion(
                                                        CoercionInputShape.Boolean,
                                                        CoercionAction.Fail)
                                                .setCoercion(
                                                        CoercionInputShape.EmptyString,
                                                        CoercionAction.Fail))
                        .withCoercionConfig(
                                LogicalType.DateTime,
                                cfg ->
                                        cfg.setCoercion(
                                                        CoercionInputShape.Integer,
                                                        CoercionAction.Fail)
                                                .setCoercion(
                                                        CoercionInputShape.Float,
                                                        CoercionAction.Fail)
                                                .setCoercion(
                                                        CoercionInputShape.Boolean,
                                                        CoercionAction.Fail)
                                                .setCoercion(
                                                        CoercionInputShape.EmptyString,
                                                        CoercionAction.Fail));
    }
}
