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
        JsonNode patchTagItems = patchProps.path("tags").path("items");
        assertThat(patchTagItems.path("minLength").asInt()).isEqualTo(1);
        assertThat(patchTagItems.path("maxLength").asInt()).isEqualTo(30);
        assertThat(patchTagItems.path("pattern").asString()).isEqualTo("^[^,;]*$");

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

        // 6. Todas las respuestas 4xx/5xx referencian ProblemDetailResponse en
        // application/problem+json y exponen 405/406/500
        for (String path : pathKeys) {
            JsonNode pathItem = paths.path(path);
            for (String method : pathItem.propertyNames()) {
                JsonNode operation = pathItem.path(method);
                JsonNode responses = operation.path("responses");
                assertThat(responses.has("405"))
                        .as("%s %s debe documentar 405", method, path)
                        .isTrue();
                assertThat(responses.has("406"))
                        .as("%s %s debe documentar 406", method, path)
                        .isTrue();
                assertThat(responses.has("500"))
                        .as("%s %s debe documentar 500", method, path)
                        .isTrue();
                for (String code : responses.propertyNames()) {
                    if (code.startsWith("4") || code.startsWith("5")) {
                        String ref =
                                responses
                                        .path(code)
                                        .path("content")
                                        .path("application/problem+json")
                                        .path("schema")
                                        .path("$ref")
                                        .asString();
                        assertThat(ref)
                                .as(
                                        "%s %s %s debe referenciar ProblemDetailResponse",
                                        method, path, code)
                                .isEqualTo("#/components/schemas/ProblemDetailResponse");
                    }
                }
            }
        }

        // 7. Verificar límites Appendix B (limit max 50, search solo por título) y 403 en GET con
        // ownership
        List<String> paginatedPaths =
                List.of(
                        "/api/v1/groups",
                        "/api/v1/groups/{groupId}/tags",
                        "/api/v1/tasks",
                        "/api/v1/groups/{groupId}/tasks",
                        "/api/v1/notifications");
        for (String paginatedPath : paginatedPaths) {
            JsonNode params = paths.path(paginatedPath).path("get").path("parameters");
            boolean foundLimit = false;
            for (JsonNode param : params) {
                if ("limit".equals(param.path("name").asString())) {
                    foundLimit = true;
                    assertThat(param.path("description").asString())
                            .contains("50")
                            .doesNotContain("100");
                }
            }
            assertThat(foundLimit).as("limit param en %s", paginatedPath).isTrue();
        }

        for (String searchPath : List.of("/api/v1/tasks", "/api/v1/groups/{groupId}/tasks")) {
            JsonNode params = paths.path(searchPath).path("get").path("parameters");
            boolean foundSearch = false;
            for (JsonNode param : params) {
                if ("search".equals(param.path("name").asString())) {
                    foundSearch = true;
                    assertThat(param.path("description").asString())
                            .containsIgnoringCase("título")
                            .doesNotContainIgnoringCase("descripción")
                            .doesNotContainIgnoringCase("etiqueta");
                }
            }
            assertThat(foundSearch).as("search param en %s", searchPath).isTrue();
        }

        for (String ownershipGet :
                List.of(
                        "/api/v1/groups/{groupId}",
                        "/api/v1/tasks/{taskId}",
                        "/api/v1/groups/{groupId}/tasks",
                        "/api/v1/groups/{groupId}/tags")) {
            assertThat(paths.path(ownershipGet).path("get").path("responses").has("403"))
                    .as("GET %s debe documentar 403 por ownership", ownershipGet)
                    .isTrue();
        }
    }
}
