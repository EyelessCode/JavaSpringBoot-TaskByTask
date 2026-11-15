package com.eyelesscode.task_by_task.shared.error;

import com.eyelesscode.task_by_task.shared.ratelimit.RateLimitException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ValidationErrorDetail> invalidParams = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            invalidParams.add(
                    new ValidationErrorDetail(
                            fieldError.getField(),
                            fieldError.getDefaultMessage() != null
                                    ? fieldError.getDefaultMessage()
                                    : "Valor inválido"));
        }
        for (ObjectError globalError : ex.getBindingResult().getGlobalErrors()) {
            invalidParams.add(
                    new ValidationErrorDetail(
                            globalError.getObjectName(),
                            globalError.getDefaultMessage() != null
                                    ? globalError.getDefaultMessage()
                                    : "Valor inválido"));
        }
        return buildValidationResponse(invalidParams, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        List<ValidationErrorDetail> invalidParams = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            invalidParams.add(
                    new ValidationErrorDetail(
                            violation.getPropertyPath().toString(), violation.getMessage()));
        }
        return buildValidationResponse(invalidParams, request);
    }

    @ExceptionHandler(FieldValidationException.class)
    public ResponseEntity<ProblemDetail> handleFieldValidation(
            FieldValidationException ex, HttpServletRequest request) {
        return buildValidationResponse(ex.getInvalidParams(), request);
    }

    private ResponseEntity<ProblemDetail> buildValidationResponse(
            List<ValidationErrorDetail> rawErrors, HttpServletRequest request) {
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST, "Uno o más campos contienen errores de validación");
        problemDetail.setTitle("Error de validación");
        problemDetail.setInstance(
                URI.create(request.getRequestURI() != null ? request.getRequestURI() : "/"));

        List<ValidationErrorDetail> sortedErrors = new ArrayList<>(rawErrors);
        sortedErrors.sort(Comparator.comparing(ValidationErrorDetail::name));
        problemDetail.setProperty("invalidParams", sortedErrors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ProblemDetail> handleBadRequest(
            BadRequestException ex, HttpServletRequest request) {
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problemDetail.setTitle(ex.getTitle());
        problemDetail.setInstance(
                URI.create(request.getRequestURI() != null ? request.getRequestURI() : "/"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ProblemDetail> handleUnreadableOrMismatch(
            Exception ex, HttpServletRequest request) {
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST,
                        "El cuerpo o parámetro de la solicitud no tiene un formato válido");
        problemDetail.setTitle("Solicitud inválida");
        problemDetail.setInstance(
                URI.create(request.getRequestURI() != null ? request.getRequestURI() : "/"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleResourceNotFound(
            ResourceNotFoundException ex, HttpServletRequest request) {
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problemDetail.setTitle("Recurso no encontrado");
        problemDetail.setInstance(
                URI.create(request.getRequestURI() != null ? request.getRequestURI() : "/"));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problemDetail);
    }

    @ExceptionHandler(ResourceConflictException.class)
    public ResponseEntity<ProblemDetail> handleResourceConflict(
            ResourceConflictException ex, HttpServletRequest request) {
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problemDetail.setTitle("Conflicto de estado");
        problemDetail.setInstance(
                URI.create(request.getRequestURI() != null ? request.getRequestURI() : "/"));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problemDetail);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleBadCredentials(
            BadCredentialsException ex, HttpServletRequest request) {
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        problemDetail.setTitle("Credenciales inválidas");
        problemDetail.setInstance(
                URI.create(request.getRequestURI() != null ? request.getRequestURI() : "/"));
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problemDetail);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.FORBIDDEN, "No tiene permisos para realizar esta acción");
        problemDetail.setTitle("Acceso denegado");
        problemDetail.setInstance(
                URI.create(request.getRequestURI() != null ? request.getRequestURI() : "/"));
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problemDetail);
    }

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(
            RateLimitException ex, HttpServletRequest request) {
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        problemDetail.setTitle("Demasiadas solicitudes");
        problemDetail.setInstance(
                URI.create(request.getRequestURI() != null ? request.getRequestURI() : "/"));
        problemDetail.setProperty("retryAfterSeconds", ex.getRetryAfterSeconds());

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(problemDetail);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnhandledException(
            Exception ex, HttpServletRequest request) {
        log.error("Error no controlado en la aplicación [URI: {}]", request.getRequestURI(), ex);
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Ocurrió un error inesperado. Intente nuevamente más tarde.");
        problemDetail.setTitle("Error interno del servidor");
        problemDetail.setInstance(
                URI.create(request.getRequestURI() != null ? request.getRequestURI() : "/"));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problemDetail);
    }
}
