package com.eyelesscode.task_by_task.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 contract configuration for Task by Task MVP (HRD-REQ-17..20). Disabled automatically
 * when {@code springdoc.api-docs.enabled=false} (default in {@code prod}).
 */
@Configuration
@ConditionalOnProperty(
        name = "springdoc.api-docs.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OpenApiConfig {

    public static final String SECURITY_SESSION_COOKIE = "sessionCookie";
    public static final String SECURITY_CSRF_TOKEN = "csrfToken";
    public static final String PROBLEM_DETAIL_SCHEMA_NAME = "ProblemDetailResponse";
    public static final String VALIDATION_ERROR_SCHEMA_NAME = "ValidationErrorDetail";
    public static final String UPDATE_TASK_PATCH_SCHEMA_NAME = "UpdateTaskPatchRequest";

    private static final String PROBLEM_JSON = "application/problem+json";

    @Bean
    public OpenAPI taskByTaskOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Task by Task API")
                                .version("1.0.0-mvp")
                                .description(
                                        """
                                        API REST del MVP de Task by Task para gestionar tareas personales por grupos y etiquetas, filtros combinados con paginación Keyset y notificaciones in-app / Web Push (VAPID RFC 8292).

                                        - **Autenticación y CSRF:** Cookie `SESSION` (`HttpOnly`, `SameSite=Lax`) y cabecera `X-XSRF-TOKEN` obtenida mediante `GET /api/v1/auth/csrf` (cookie `XSRF-TOKEN`).
                                        - **Fechas y zonas horarias:** Los instantes se almacenan en UTC y la zona horaria del usuario se expresa como `ZoneId` IANA (ej. `America/Lima`, `UTC`). Las preferencias de hora soportan `24H` y `12H`.
                                        - **Regla de vencimiento y avisos (RF-14, RF-28):** Las tareas solo con fecha (`yyyy-MM-dd`) disparan su aviso a las `09:00 AM` hora local del usuario, pero solo se consideran vencidas al finalizar el día (`23:59:59.999` hora local).
                                        - **Errores:** Todas las respuestas de error siguen RFC 7807 (`application/problem+json`) en español.
                                        """))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        SECURITY_SESSION_COOKIE,
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.COOKIE)
                                                .name("SESSION")
                                                .description(
                                                        "Cookie de sesión HttpOnly emitida tras"
                                                                + " registro o inicio de sesión"))
                                .addSecuritySchemes(
                                        SECURITY_CSRF_TOKEN,
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.HEADER)
                                                .name("X-XSRF-TOKEN")
                                                .description(
                                                        "Token anti-CSRF obtenido desde GET"
                                                                + " /api/v1/auth/csrf y"
                                                                + " sincronizado con la cookie"
                                                                + " XSRF-TOKEN")))
                .addSecurityItem(
                        new SecurityRequirement()
                                .addList(SECURITY_SESSION_COOKIE)
                                .addList(SECURITY_CSRF_TOKEN));
    }

    @Bean
    public OpenApiCustomizer taskByTaskOpenApiCustomizer() {
        return openApi -> {
            Components components = openApi.getComponents();
            if (components == null) {
                components = new Components();
                openApi.setComponents(components);
            }

            components.addSchemas(VALIDATION_ERROR_SCHEMA_NAME, buildValidationErrorSchema());
            components.addSchemas(PROBLEM_DETAIL_SCHEMA_NAME, buildProblemDetailSchema());
            components.addSchemas(UPDATE_TASK_PATCH_SCHEMA_NAME, buildUpdateTaskPatchSchema());

            if (components.getSchemas() != null) {
                List<String> internalSchemas =
                        components.getSchemas().keySet().stream()
                                .filter(
                                        name ->
                                                name.startsWith("PatchField")
                                                        || name.equals("UpdateTaskRequest"))
                                .toList();
                internalSchemas.forEach(components.getSchemas()::remove);
            }

            if (openApi.getPaths() == null) {
                return;
            }

            PathItem taskByIdPath = openApi.getPaths().get("/api/v1/tasks/{taskId}");
            if (taskByIdPath != null
                    && taskByIdPath.getPatch() != null
                    && taskByIdPath.getPatch().getRequestBody() != null
                    && taskByIdPath.getPatch().getRequestBody().getContent() != null) {
                MediaType jsonMedia =
                        taskByIdPath
                                .getPatch()
                                .getRequestBody()
                                .getContent()
                                .get("application/json");
                if (jsonMedia != null) {
                    jsonMedia.setSchema(
                            new Schema<>()
                                    .$ref("#/components/schemas/" + UPDATE_TASK_PATCH_SCHEMA_NAME));
                }
            }

            openApi.getPaths()
                    .forEach(
                            (path, pathItem) ->
                                    pathItem.readOperations()
                                            .forEach(
                                                    operation -> {
                                                        ApiResponses responses =
                                                                operation.getResponses();
                                                        if (responses == null) {
                                                            responses = new ApiResponses();
                                                            operation.setResponses(responses);
                                                        }
                                                        responses.putIfAbsent(
                                                                "406",
                                                                problemResponse(
                                                                        "Formato de respuesta no"
                                                                                + " aceptable"));
                                                        responses.putIfAbsent(
                                                                "500",
                                                                problemResponse(
                                                                        "Error interno del"
                                                                                + " servidor"));
                                                    }));
        };
    }

    private static Schema<?> buildValidationErrorSchema() {
        return new ObjectSchema()
                .description("Detalle individual de error de validación de campo")
                .addProperty(
                        "name",
                        new StringSchema()
                                .description("Nombre del campo o parámetro inválido")
                                .example("title"))
                .addProperty(
                        "reason",
                        new StringSchema()
                                .description("Mensaje de validación en español")
                                .example("El título debe tener entre 2 y 100 caracteres"));
    }

    private static Schema<?> buildProblemDetailSchema() {
        return new ObjectSchema()
                .description("Respuesta de error estándar RFC 7807 en español")
                .addProperty("type", new StringSchema().format("uri").example("about:blank"))
                .addProperty(
                        "title",
                        new StringSchema()
                                .description("Resumen breve del error en español")
                                .example("Error de validación"))
                .addProperty(
                        "status",
                        new IntegerSchema().description("Código de estado HTTP").example(400))
                .addProperty(
                        "detail",
                        new StringSchema()
                                .description("Descripción detallada en español")
                                .example("Uno o más campos contienen errores de validación"))
                .addProperty(
                        "instance",
                        new StringSchema()
                                .format("uri")
                                .description("Ruta del recurso donde ocurrió el error")
                                .example("/api/v1/tasks"))
                .addProperty(
                        "invalidParams",
                        new ArraySchema()
                                .items(
                                        new Schema<>()
                                                .$ref(
                                                        "#/components/schemas/"
                                                                + VALIDATION_ERROR_SCHEMA_NAME))
                                .description(
                                        "Lista ordenada de campos inválidos (solo en errores 400"
                                                + " de validación)"))
                .addProperty(
                        "retryAfterSeconds",
                        new IntegerSchema()
                                .format("int64")
                                .description(
                                        "Segundos de espera sugeridos antes de reintentar (solo en"
                                                + " 429)"));
    }

    private static Schema<?> buildUpdateTaskPatchSchema() {
        Schema<?> titleSchema =
                new StringSchema()
                        .minLength(2)
                        .maxLength(100)
                        .nullable(false)
                        .description(
                                "Nuevo título de la tarea (2 a 100 caracteres; si se incluye no"
                                        + " puede ser nulo)")
                        .example("Entregar informe mensual");

        Schema<?> descriptionSchema =
                new StringSchema()
                        .maxLength(750)
                        .nullable(true)
                        .description(
                                "Nueva descripción de la tarea (máx. 750 caracteres; enviar null"
                                        + " para borrar)")
                        .example("Incluir métricas del último trimestre");

        Schema<?> dueDateSchema =
                new StringSchema()
                        .format("date")
                        .nullable(true)
                        .description(
                                "Nueva fecha límite ISO-8601 (yyyy-MM-dd; enviar null para quitar"
                                        + " fecha y hora)")
                        .example("2026-12-01");

        Schema<?> dueTimeSchema =
                new StringSchema()
                        .format("time")
                        .nullable(true)
                        .description(
                                "Nueva hora límite ISO-8601 (HH:mm[:ss]; requiere fecha límite;"
                                        + " enviar null para dejar solo fecha)")
                        .example("14:30:00");

        Schema<?> tagsSchema =
                new ArraySchema()
                        .items(new StringSchema().maxLength(25).example("urgente"))
                        .nullable(true)
                        .description(
                                "Lista completa de etiquetas de la tarea (reemplaza las actuales;"
                                        + " enviar null o [] para quitar todas)");

        ObjectSchema schema = new ObjectSchema();
        schema.setDescription(
                "Actualización parcial de una tarea (PATCH). Los campos omitidos conservan su"
                        + " valor actual; description, dueDate, dueTime y tags aceptan null para"
                        + " limpiarse.");
        schema.setProperties(
                Map.of(
                        "title", titleSchema,
                        "description", descriptionSchema,
                        "dueDate", dueDateSchema,
                        "dueTime", dueTimeSchema,
                        "tags", tagsSchema));
        return schema;
    }

    private static ApiResponse problemResponse(String description) {
        return new ApiResponse()
                .description(description)
                .content(
                        new Content()
                                .addMediaType(
                                        PROBLEM_JSON,
                                        new MediaType()
                                                .schema(
                                                        new Schema<>()
                                                                .$ref(
                                                                        "#/components/schemas/"
                                                                                + PROBLEM_DETAIL_SCHEMA_NAME))));
    }
}
