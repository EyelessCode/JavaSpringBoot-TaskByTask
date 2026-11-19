package com.eyelesscode.task_by_task.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eyelesscode.task_by_task.auth.dto.LoginRequest;
import com.eyelesscode.task_by_task.group.dto.CreateGroupRequest;
import com.eyelesscode.task_by_task.notification.dto.RegisterPushSubscriptionRequest;
import com.eyelesscode.task_by_task.tag.dto.CreateTagRequest;
import com.eyelesscode.task_by_task.task.dto.CreateTaskRequest;
import com.eyelesscode.task_by_task.task.dto.TaskTargetGroupRequest;
import com.eyelesscode.task_by_task.task.dto.UpdateTaskCompletionRequest;
import com.eyelesscode.task_by_task.user.dto.UpdateProfileRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

class JacksonStrictCoercionTest {

    private JsonMapper mapper;

    @BeforeEach
    void setUp() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new JacksonConfig().strictJsonMapperCustomizer().customize(builder);
        this.mapper = builder.build();
    }

    @Test
    @DisplayName("Rechaza coerción escalar hacia String (Integer, Float, Boolean) (HRD-REQ-07)")
    void shouldRejectCoercionToString() {
        assertThatThrownBy(() -> mapper.readValue("{\"name\": 123}", CreateGroupRequest.class))
                .isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"name\": 12.5}", CreateGroupRequest.class))
                .isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"name\": true}", CreateGroupRequest.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    @DisplayName("Rechaza coerción escalar hacia Boolean (String, Integer, EmptyString)")
    void shouldRejectCoercionToBoolean() {
        assertThatThrownBy(
                        () ->
                                mapper.readValue(
                                        "{\"completed\": \"true\"}",
                                        UpdateTaskCompletionRequest.class))
                .isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(
                        () ->
                                mapper.readValue(
                                        "{\"completed\": 1}", UpdateTaskCompletionRequest.class))
                .isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(
                        () ->
                                mapper.readValue(
                                        "{\"completed\": \"\"}", UpdateTaskCompletionRequest.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    @DisplayName("Rechaza coerción escalar hacia Integer/Long (String, Float, Boolean)")
    void shouldRejectCoercionToInteger() {
        assertThatThrownBy(
                        () ->
                                mapper.readValue(
                                        "{\"targetGroupId\": \"1\"}", TaskTargetGroupRequest.class))
                .isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(
                        () ->
                                mapper.readValue(
                                        "{\"targetGroupId\": 1.5}", TaskTargetGroupRequest.class))
                .isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(
                        () ->
                                mapper.readValue(
                                        "{\"targetGroupId\": true}", TaskTargetGroupRequest.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    @DisplayName("Rechaza coerción escalar hacia DateTime (Integer, Boolean, EmptyString)")
    void shouldRejectCoercionToDateTime() {
        assertThatThrownBy(
                        () ->
                                mapper.readValue(
                                        "{\"title\": \"Tarea\", \"dueDate\": 12345}",
                                        CreateTaskRequest.class))
                .isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(
                        () ->
                                mapper.readValue(
                                        "{\"title\": \"Tarea\", \"dueDate\": true}",
                                        CreateTaskRequest.class))
                .isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(
                        () ->
                                mapper.readValue(
                                        "{\"title\": \"Tarea\", \"dueDate\": \"\"}",
                                        CreateTaskRequest.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    @DisplayName("Ignora propiedades JSON desconocidas de forma uniforme en los 6 módulos")
    void shouldIgnoreUnknownPropertiesAcrossAllSixModules() {
        LoginRequest login =
                mapper.readValue(
                        "{\"email\": \"a@b.com\", \"password\": \"secret123\", \"extra\": 99}",
                        LoginRequest.class);
        assertThat(login.email()).isEqualTo("a@b.com");

        UpdateProfileRequest profile =
                mapper.readValue(
                        "{\"displayName\": \"Ana\", \"unknownField\": \"ignored\"}",
                        UpdateProfileRequest.class);
        assertThat(profile.displayName()).isEqualTo("Ana");

        CreateGroupRequest group =
                mapper.readValue(
                        "{\"name\": \"Trabajo\", \"unexpected\": true}", CreateGroupRequest.class);
        assertThat(group.name()).isEqualTo("Trabajo");

        CreateTagRequest tag =
                mapper.readValue(
                        "{\"name\": \"urgente\", \"extra\": [1,2]}", CreateTagRequest.class);
        assertThat(tag.name()).isEqualTo("urgente");

        CreateTaskRequest task =
                mapper.readValue(
                        "{\"title\": \"Revisar PR\", \"unknown\": \"x\"}", CreateTaskRequest.class);
        assertThat(task.title()).isEqualTo("Revisar PR");

        RegisterPushSubscriptionRequest push =
                mapper.readValue(
                        "{\"endpoint\": \"https://push.example/1\", \"p256dh\": \"k1\", \"auth\":"
                                + " \"a1\", \"extra\": 1}",
                        RegisterPushSubscriptionRequest.class);
        assertThat(push.endpoint()).isEqualTo("https://push.example/1");
    }
}
