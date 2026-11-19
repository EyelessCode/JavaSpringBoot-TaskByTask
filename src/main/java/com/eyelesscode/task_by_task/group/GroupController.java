package com.eyelesscode.task_by_task.group;

import com.eyelesscode.task_by_task.group.dto.CreateGroupRequest;
import com.eyelesscode.task_by_task.group.dto.GroupResponse;
import com.eyelesscode.task_by_task.group.dto.UpdateGroupRequest;
import com.eyelesscode.task_by_task.shared.config.OpenApiConfig;
import com.eyelesscode.task_by_task.shared.pagination.KeysetPage;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups")
@Tag(name = "Groups", description = "Gestión de grupos personales del usuario")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @PostMapping
    @Operation(summary = "Crear un nuevo grupo de tareas")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Grupo creado correctamente"),
        @ApiResponse(responseCode = "400", description = "Error de validación (nombre 3–30)"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido"),
        @ApiResponse(responseCode = "415", description = "Tipo de medio no soportado")
    })
    public ResponseEntity<GroupResponse> createGroup(
            @Valid @RequestBody CreateGroupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(groupService.createGroup(request));
    }

    @GetMapping
    @SecurityRequirements({@SecurityRequirement(name = OpenApiConfig.SECURITY_SESSION_COOKIE)})
    @Operation(
            summary = "Listar grupos del usuario con paginación Keyset",
            description =
                    "Ordena siempre el grupo predeterminado 'Personal' primero, seguido de los"
                            + " demás grupos por fecha de creación ascendente.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado paginado de grupos"),
        @ApiResponse(responseCode = "400", description = "Cursor o límite inválido"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado")
    })
    public ResponseEntity<KeysetPage<GroupResponse>> listGroups(
            @Parameter(description = "Cursor opaco Base64URL de la página anterior")
                    @RequestParam(required = false)
                    String cursor,
            @Parameter(description = "Cantidad máxima de elementos por página (1 a 100)")
                    @RequestParam(defaultValue = "20")
                    int limit) {
        return ResponseEntity.ok(groupService.listGroups(cursor, limit));
    }

    @GetMapping("/{groupId}")
    @SecurityRequirements({@SecurityRequirement(name = OpenApiConfig.SECURITY_SESSION_COOKIE)})
    @Operation(summary = "Consultar el detalle de un grupo")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Detalle del grupo"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(responseCode = "404", description = "Grupo no encontrado")
    })
    public ResponseEntity<GroupResponse> getGroup(
            @Parameter(description = "Identificador único del grupo", example = "1") @PathVariable
                    Long groupId) {
        return ResponseEntity.ok(groupService.getGroup(groupId));
    }

    @PatchMapping("/{groupId}")
    @Operation(
            summary = "Renombrar un grupo (incluido el grupo Personal)",
            description = "Actualiza el nombre del grupo respetando el rango de 3 a 30 caracteres.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Grupo renombrado correctamente"),
        @ApiResponse(responseCode = "400", description = "Error de validación"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido"),
        @ApiResponse(responseCode = "404", description = "Grupo no encontrado"),
        @ApiResponse(responseCode = "415", description = "Tipo de medio no soportado")
    })
    public ResponseEntity<GroupResponse> renameGroup(
            @Parameter(description = "Identificador único del grupo", example = "1") @PathVariable
                    Long groupId,
            @Valid @RequestBody UpdateGroupRequest request) {
        return ResponseEntity.ok(groupService.renameGroup(groupId, request));
    }

    @DeleteMapping("/{groupId}")
    @Operation(
            summary = "Eliminar un grupo y sus tareas/etiquetas asociadas",
            description =
                    "Elimina el grupo y cancela los recordatorios programados de sus tareas. El"
                            + " grupo predeterminado 'Personal' no puede eliminarse (HTTP 409).")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Grupo eliminado correctamente"),
        @ApiResponse(responseCode = "401", description = "Usuario no autenticado"),
        @ApiResponse(responseCode = "403", description = "Token CSRF ausente o inválido"),
        @ApiResponse(responseCode = "404", description = "Grupo no encontrado"),
        @ApiResponse(responseCode = "409", description = "El grupo Personal no puede ser eliminado")
    })
    public ResponseEntity<Void> deleteGroup(
            @Parameter(description = "Identificador único del grupo", example = "2") @PathVariable
                    Long groupId) {
        groupService.deleteGroup(groupId);
        return ResponseEntity.noContent().build();
    }
}
