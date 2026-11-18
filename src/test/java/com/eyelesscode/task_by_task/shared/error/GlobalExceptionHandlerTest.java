package com.eyelesscode.task_by_task.shared.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eyelesscode.task_by_task.shared.ratelimit.RateLimitException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(new TestController())
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    record SampleRequestDto(
            @NotBlank(message = "El nombre es obligatorio") String name,
            @Min(value = 1, message = "El valor debe ser positivo") int value) {}

    @RestController
    @RequestMapping("/test-errors")
    static class TestController {

        @PostMapping("/validation")
        void triggerValidation(@Valid @RequestBody SampleRequestDto dto) {}

        @GetMapping("/field-validation")
        void triggerFieldValidation() {
            throw new FieldValidationException(
                    List.of(
                            new ValidationErrorDetail(
                                    "title", "El título debe tener entre 2 y 100 caracteres"),
                            new ValidationErrorDetail(
                                    "tags[0]",
                                    "La etiqueta no puede contener comas ni punto y coma")));
        }

        @GetMapping("/bad-request")
        void triggerBadRequest() {
            throw new BadRequestException("El cursor de paginación proporcionado no es válido");
        }

        @GetMapping("/not-found")
        void triggerNotFound() {
            throw new ResourceNotFoundException("Tarea con id 42 no encontrada");
        }

        @GetMapping("/conflict")
        void triggerConflict() {
            throw new ResourceConflictException(
                    "El grupo Personal no puede ser eliminado ni convertido en compartido");
        }

        @GetMapping("/forbidden")
        void triggerForbidden() {
            throw new AccessDeniedException("No autorizado");
        }

        @GetMapping("/rate-limit")
        void triggerRateLimit() {
            throw new RateLimitException(45);
        }

        @GetMapping("/server-error")
        void triggerServerError() {
            throw new RuntimeException("Fallo crítico interno con datos sensibles");
        }

        @GetMapping("/missing-param")
        void triggerMissingParam(@RequestParam("endpoint") String endpoint) {}
    }

    @Test
    @DisplayName("400: Error de validación devuelve RFC 7807 con invalidParams estructurados")
    void shouldHandleMethodArgumentNotValidException() throws Exception {
        mockMvc.perform(
                        post("/test-errors/validation")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"\", \"value\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Error de validación"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail")
                                .value("Uno o más campos contienen errores de validación"))
                .andExpect(jsonPath("$.instance").value("/test-errors/validation"))
                .andExpect(jsonPath("$.invalidParams").isArray())
                .andExpect(jsonPath("$.invalidParams", hasSize(2)))
                .andExpect(jsonPath("$.invalidParams[0].name").value("name"))
                .andExpect(jsonPath("$.invalidParams[0].reason").value("El nombre es obligatorio"))
                .andExpect(jsonPath("$.invalidParams[1].name").value("value"))
                .andExpect(
                        jsonPath("$.invalidParams[1].reason").value("El valor debe ser positivo"));
    }

    @Test
    @DisplayName(
            "400: FieldValidationException devuelve RFC 7807 con invalidParams ordenados"
                    + " (TSK-REQ-27)")
    void shouldHandleFieldValidationException() throws Exception {
        mockMvc.perform(get("/test-errors/field-validation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Error de validación"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail")
                                .value("Uno o más campos contienen errores de validación"))
                .andExpect(jsonPath("$.instance").value("/test-errors/field-validation"))
                .andExpect(jsonPath("$.invalidParams", hasSize(2)))
                .andExpect(jsonPath("$.invalidParams[0].name").value("tags[0]"))
                .andExpect(
                        jsonPath("$.invalidParams[0].reason")
                                .value("La etiqueta no puede contener comas ni punto y coma"))
                .andExpect(jsonPath("$.invalidParams[1].name").value("title"))
                .andExpect(
                        jsonPath("$.invalidParams[1].reason")
                                .value("El título debe tener entre 2 y 100 caracteres"));
    }

    @Test
    @DisplayName("400: BadRequestException devuelve RFC 7807 con título y detalle en español")
    void shouldHandleBadRequestException() throws Exception {
        mockMvc.perform(get("/test-errors/bad-request"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail")
                                .value("El cursor de paginación proporcionado no es válido"))
                .andExpect(jsonPath("$.instance").value("/test-errors/bad-request"));
    }

    @Test
    @DisplayName("400: JSON malformado devuelve RFC 7807 como Solicitud inválida")
    void shouldHandleMalformedJsonPayload() throws Exception {
        mockMvc.perform(
                        post("/test-errors/validation")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": brokenJsonPayload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "El cuerpo o parámetro de la solicitud no tiene un formato"
                                                + " válido"))
                .andExpect(jsonPath("$.instance").value("/test-errors/validation"));
    }

    @Test
    @DisplayName("404: ResourceNotFoundException devuelve RFC 7807 con título y detalle en español")
    void shouldHandleResourceNotFoundException() throws Exception {
        mockMvc.perform(get("/test-errors/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso no encontrado"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Tarea con id 42 no encontrada"))
                .andExpect(jsonPath("$.instance").value("/test-errors/not-found"));
    }

    @Test
    @DisplayName("409: ResourceConflictException devuelve RFC 7807 de Conflicto de estado")
    void shouldHandleResourceConflictException() throws Exception {
        mockMvc.perform(get("/test-errors/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflicto de estado"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "El grupo Personal no puede ser eliminado ni convertido en"
                                                + " compartido"))
                .andExpect(jsonPath("$.instance").value("/test-errors/conflict"));
    }

    @Test
    @DisplayName("403: AccessDeniedException devuelve RFC 7807 de Acceso denegado en español")
    void shouldHandleAccessDeniedException() throws Exception {
        mockMvc.perform(get("/test-errors/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Acceso denegado"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(
                        jsonPath("$.detail").value("No tiene permisos para realizar esta acción"))
                .andExpect(jsonPath("$.instance").value("/test-errors/forbidden"));
    }

    @Test
    @DisplayName(
            "429: RateLimitException devuelve RFC 7807 con cabecera Retry-After y título en"
                    + " español")
    void shouldHandleRateLimitException() throws Exception {
        mockMvc.perform(get("/test-errors/rate-limit"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "45"))
                .andExpect(jsonPath("$.title").value("Demasiadas solicitudes"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.retryAfterSeconds").value(45))
                .andExpect(jsonPath("$.detail").value(containsString("45 segundos")))
                .andExpect(jsonPath("$.instance").value("/test-errors/rate-limit"));
    }

    @Test
    @DisplayName(
            "500: Excepción no controlada devuelve RFC 7807 genérico seguro sin filtrar trazas"
                    + " internas")
    void shouldHandleGeneralUnexpectedException() throws Exception {
        mockMvc.perform(get("/test-errors/server-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Error interno del servidor"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "Ocurrió un error inesperado. Intente nuevamente más"
                                                + " tarde."))
                .andExpect(jsonPath("$.instance").value("/test-errors/server-error"))
                .andExpect(
                        content()
                                .string(
                                        not(
                                                containsString(
                                                        "Fallo crítico interno con datos"
                                                                + " sensibles"))));
    }

    @Test
    @DisplayName(
            "400: MissingServletRequestParameterException devuelve RFC 7807 como Solicitud"
                    + " inválida (NTF-REQ-09)")
    void shouldHandleMissingServletRequestParameterException() throws Exception {
        mockMvc.perform(get("/test-errors/missing-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "El parámetro 'endpoint' es obligatorio y no puede estar"
                                                + " vacío"))
                .andExpect(jsonPath("$.instance").value("/test-errors/missing-param"));
    }
}
