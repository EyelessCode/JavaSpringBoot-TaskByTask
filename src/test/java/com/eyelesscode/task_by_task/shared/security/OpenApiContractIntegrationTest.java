package com.eyelesscode.task_by_task.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eyelesscode.task_by_task.shared.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenApiContractIntegrationTest extends AbstractIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName(
            "Expone /v3/api-docs OpenAPI 3 válido con metadatos en español, esquemas de seguridad,"
                    + " 100% rutas MVP, esquema PATCH plano y cero rutas Posteriores"
                    + " (HRD-REQ-17..19, HRD-REQ-22)")
    void shouldExposeCompleteMvpOpenApiContractWithoutPosteriorFeatures() throws Exception {
        MockMvc mockMvc =
                MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        MvcResult result =
                mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());

        // 1. Metadatos OpenAPI 3 en español
        assertThat(root.path("openapi").asString()).startsWith("3.");
        assertThat(root.path("info").path("title").asString()).isEqualTo("Task by Task API");
        assertThat(root.path("info").path("version").asString()).isEqualTo("1.0.0-mvp");
        assertThat(root.path("info").path("description").asString())
                .contains("SESSION")
                .contains("X-XSRF-TOKEN")
                .contains("09:00 AM");

        // 2. Esquemas de seguridad (sessionCookie + csrfToken)
        JsonNode securitySchemes = root.path("components").path("securitySchemes");
        assertThat(securitySchemes.path("sessionCookie").path("type").asString())
                .isEqualTo("apiKey");
        assertThat(securitySchemes.path("sessionCookie").path("in").asString()).isEqualTo("cookie");
        assertThat(securitySchemes.path("sessionCookie").path("name").asString())
                .isEqualTo("SESSION");
        assertThat(securitySchemes.path("csrfToken").path("type").asString()).isEqualTo("apiKey");
        assertThat(securitySchemes.path("csrfToken").path("in").asString()).isEqualTo("header");
        assertThat(securitySchemes.path("csrfToken").path("name").asString())
                .isEqualTo("X-XSRF-TOKEN");

        // 3. Esquemas compartidos RFC 7807 y esquema PATCH plano sin PatchField*
        JsonNode schemas = root.path("components").path("schemas");
        List<String> schemaNames = new ArrayList<>();
        schemas.propertyNames().forEach(schemaNames::add);

        assertThat(schemaNames)
                .contains(
                        "ProblemDetailResponse", "ValidationErrorDetail", "UpdateTaskPatchRequest")
                .noneMatch(name -> name.startsWith("PatchField"));

        JsonNode patchSchema = schemas.path("UpdateTaskPatchRequest");
        JsonNode patchProps = patchSchema.path("properties");
        assertThat(patchProps.has("title")).isTrue();
        assertThat(patchProps.path("title").path("minLength").asInt()).isEqualTo(2);
        assertThat(patchProps.path("title").path("maxLength").asInt()).isEqualTo(100);
        assertThat(patchProps.has("description")).isTrue();
        assertThat(patchProps.has("dueDate")).isTrue();
        assertThat(patchProps.has("dueTime")).isTrue();
        assertThat(patchProps.has("tags")).isTrue();

        JsonNode patchTaskRef =
                root.path("paths")
                        .path("/api/v1/tasks/{taskId}")
                        .path("patch")
                        .path("requestBody")
                        .path("content")
                        .path("application/json")
                        .path("schema")
                        .path("$ref");
        assertThat(patchTaskRef.asString())
                .isEqualTo("#/components/schemas/UpdateTaskPatchRequest");

        // 4. Cobertura del 100% de rutas MVP de los 6 módulos
        JsonNode paths = root.path("paths");
        List<String> pathKeys = new ArrayList<>();
        paths.propertyNames().forEach(pathKeys::add);

        assertThat(pathKeys)
                .contains(
                        "/api/v1/auth/csrf",
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/logout",
                        "/api/v1/auth/recover-password",
                        "/api/v1/users/me",
                        "/api/v1/users/me/password",
                        "/api/v1/users/me/recovery-codes/regenerate",
                        "/api/v1/groups",
                        "/api/v1/groups/{groupId}",
                        "/api/v1/groups/{groupId}/tags",
                        "/api/v1/groups/{groupId}/tags/{tagId}",
                        "/api/v1/groups/{groupId}/tasks",
                        "/api/v1/tasks",
                        "/api/v1/tasks/{taskId}",
                        "/api/v1/tasks/{taskId}/completion",
                        "/api/v1/tasks/{taskId}/tags/{tagId}",
                        "/api/v1/tasks/{taskId}/move",
                        "/api/v1/tasks/{taskId}/copy",
                        "/api/v1/notifications/push/vapid-public-key",
                        "/api/v1/notifications/push/subscriptions",
                        "/api/v1/notifications/push/subscriptions/{subscriptionId}",
                        "/api/v1/notifications",
                        "/api/v1/notifications/unread-count",
                        "/api/v1/notifications/{notificationId}/read",
                        "/api/v1/notifications/read-all",
                        "/api/v1/notifications/{notificationId}");

        // 5. Cero rutas de Fase Posterior o funcionalidades excluidas
        List<String> forbiddenSegments =
                List.of(
                        "invitation",
                        "member",
                        "subtask",
                        "comment",
                        "attachment",
                        "priority",
                        "priorities",
                        "oauth",
                        "audit",
                        "calendar",
                        "export",
                        "import",
                        "recurring",
                        "shared");
        for (String path : pathKeys) {
            String lower = path.toLowerCase(Locale.ROOT);
            for (String forbidden : forbiddenSegments) {
                assertThat(lower)
                        .as("La ruta %s no debe exponer concepto Posterior '%s'", path, forbidden)
                        .doesNotContain(forbidden);
            }
        }
    }
}
