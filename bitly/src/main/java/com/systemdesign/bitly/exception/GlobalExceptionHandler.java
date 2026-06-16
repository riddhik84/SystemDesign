package com.systemdesign.bitly.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Centralised error handling.  All responses use the RFC 7807 Problem Detail format (supported
 * natively in Spring Boot 3.x via {@link ProblemDetail}), which makes error payloads consistent
 * and machine-parseable.
 *
 * <p>HTTP status mapping:
 * <ul>
 *   <li>404 Not Found   — {@link UrlNotFoundException} or Spring's NoResourceFoundException</li>
 *   <li>409 Conflict    — {@link AliasAlreadyExistsException}</li>
 *   <li>410 Gone        — {@link UrlExpiredException}</li>
 *   <li>422 Unprocessable Entity — Bean Validation failures</li>
 *   <li>4xx/5xx (passthrough) — Spring {@link ResponseStatusException} subclasses</li>
 *   <li>500 Internal Server Error — all other unchecked exceptions</li>
 * </ul>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final String TIMESTAMP_KEY = "timestamp";

    @ExceptionHandler(UrlNotFoundException.class)
    public ProblemDetail handleNotFound(UrlNotFoundException ex) {
        log.debug("URL not found: {}", ex.getShortCode());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://bitly.example.com/errors/url-not-found"));
        pd.setTitle("URL Not Found");
        pd.setProperty(TIMESTAMP_KEY, Instant.now());
        pd.setProperty("short_code", ex.getShortCode());
        return pd;
    }

    @ExceptionHandler(UrlExpiredException.class)
    public ProblemDetail handleExpired(UrlExpiredException ex) {
        log.debug("URL expired: shortCode={}, expiredAt={}", ex.getShortCode(), ex.getExpiredAt());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.GONE, ex.getMessage());
        pd.setType(URI.create("https://bitly.example.com/errors/url-expired"));
        pd.setTitle("URL Expired");
        pd.setProperty(TIMESTAMP_KEY, Instant.now());
        pd.setProperty("short_code", ex.getShortCode());
        pd.setProperty("expired_at", ex.getExpiredAt());
        return pd;
    }

    @ExceptionHandler(AliasAlreadyExistsException.class)
    public ProblemDetail handleAliasConflict(AliasAlreadyExistsException ex) {
        log.debug("Alias conflict: {}", ex.getAlias());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create("https://bitly.example.com/errors/alias-conflict"));
        pd.setTitle("Alias Already Exists");
        pd.setProperty(TIMESTAMP_KEY, Instant.now());
        pd.setProperty("alias", ex.getAlias());
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(
                FieldError::getField,
                fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid value",
                (first, second) -> first   // keep first message on duplicate field
            ));
        log.debug("Validation failed: {}", fieldErrors);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Request validation failed"
        );
        pd.setType(URI.create("https://bitly.example.com/errors/validation-failed"));
        pd.setTitle("Validation Failed");
        pd.setProperty(TIMESTAMP_KEY, Instant.now());
        pd.setProperty("field_errors", fieldErrors);
        return pd;
    }

    /**
     * Pass-through handler for Spring's own {@link ResponseStatusException} hierarchy
     * (e.g. {@code NoResourceFoundException} → 404, {@code MethodNotAllowedException} → 405).
     * These exceptions already carry the correct HTTP status; we just wrap them in Problem Detail
     * so the response format stays consistent.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(ResponseStatusException ex) {
        HttpStatusCode status = ex.getStatusCode();
        if (status.is5xxServerError()) {
            log.error("Server-side ResponseStatusException", ex);
        } else {
            log.debug("Client-side ResponseStatusException: {} {}", status.value(), ex.getReason());
        }
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status,
            ex.getReason() != null ? ex.getReason() : ex.getMessage());
        pd.setProperty(TIMESTAMP_KEY, Instant.now());
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        // Spring MVC 6.1+ uses exceptions that implement ErrorResponse (e.g. NoResourceFoundException)
        // but do NOT extend ResponseStatusException. Detect and passthrough those here.
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            log.debug("ErrorResponse exception: {} - {}", status.value(), ex.getMessage());
            ProblemDetail pd = errorResponse.getBody();
            pd.setProperty(TIMESTAMP_KEY, Instant.now());
            return pd;
        }

        log.error("Unhandled exception", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred"
        );
        pd.setType(URI.create("https://bitly.example.com/errors/internal-error"));
        pd.setTitle("Internal Server Error");
        pd.setProperty(TIMESTAMP_KEY, Instant.now());
        return pd;
    }
}
