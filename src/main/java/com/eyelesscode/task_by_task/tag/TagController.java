package com.eyelesscode.task_by_task.tag;

import com.eyelesscode.task_by_task.shared.config.OpenApiConfig;
import com.eyelesscode.task_by_task.shared.pagination.KeysetPage;
import com.eyelesscode.task_by_task.tag.dto.CreateTagRequest;
import com.eyelesscode.task_by_task.tag.dto.TagResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups/{groupId}/tags")
@Tag(
        name = "Tags",
        description = "Gestión de etiquetas por grupo (unicidad insensible a mayúsculas)")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping
    @SecurityRequirements({@SecurityRequirement(name = OpenApiConfig.SECURITY_SESSION_COOKIE)})
    @Operation(
            summary = "Listar o buscar etiquetas de un grupo con paginación Keyset",
            description =
                    "Devuelve las etiquetas del grupo ordenadas alfabéticamente (insensible a"
                            + " mayúsculas) con filtro opcional de búsqueda.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado paginado de etiquetas"),
        @ApiResponse(responseCode = "400", description = "Cursor o límite inválido"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(responseCode = "404", description = "Grupo no encontrado")
    })
    public ResponseEntity<KeysetPage<TagResponse>> listGroupTags(
            @Parameter(description = "Identificador del grupo", example = "1") @PathVariable
                    Long groupId,
            @Parameter(description = "Texto parcial para filtrar etiquetas por nombre")
                    @RequestParam(required = false)
                    String query,
            @Parameter(description = "Cursor opaco Base64URL de la página anterior")
                    @RequestParam(required = false)
                    String cursor,
            @Parameter(description = "Cantidad máxima de elementos por página (1 a 100)")
                    @RequestParam(defaultValue = "20")
                    int limit) {
        return ResponseEntity.ok(tagService.listGroupTags(groupId, query, cursor, limit));
    }

    @PostMapping
    @Operation(
            summary = "Crear o reutilizar una etiqueta dentro de un grupo",
            description =
                    "Si ya existe una etiqueta equivalente (`TRIM(LOWER(name))`) en el grupo,"
                            + " devuelve HTTP 200 con la existente; si es nueva, devuelve HTTP"
                            + " 201.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Etiqueta existente reutilizada"),
        @ApiResponse(responseCode = "201", description = "Etiqueta creada correctamente"),
        @ApiResponse(
                responseCode = "400",
                description = "Error de validación (máx. 25 caracteres, sin comas ni ';')"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido"),
        @ApiResponse(responseCode = "404", description = "Grupo no encontrado"),
        @ApiResponse(responseCode = "415", description = "Tipo de medio no soportado")
    })
    public ResponseEntity<TagResponse> createOrReuseTag(
            @Parameter(description = "Identificador del grupo", example = "1") @PathVariable
                    Long groupId,
            @Valid @RequestBody CreateTagRequest request) {
        TagService.TagCreationResult result = tagService.createOrReuseTag(groupId, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }

    @DeleteMapping("/{tagId}")
    @Operation(
            summary = "Eliminar permanentemente una etiqueta del grupo",
            description =
                    "Borra la etiqueta del grupo y la desasocia de todas las tareas del grupo que"
                            + " la utilizaban.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Etiqueta eliminada del grupo"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido"),
        @ApiResponse(responseCode = "404", description = "Grupo o etiqueta no encontrados")
    })
    public ResponseEntity<Void> deleteGroupTag(
            @Parameter(description = "Identificador del grupo", example = "1") @PathVariable
                    Long groupId,
            @Parameter(description = "Identificador de la etiqueta", example = "10") @PathVariable
                    Long tagId) {
        tagService.deleteGroupTag(groupId, tagId);
        return ResponseEntity.noContent().build();
    }
}
