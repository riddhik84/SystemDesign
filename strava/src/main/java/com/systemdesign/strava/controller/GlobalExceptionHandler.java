package com.systemdesign.strava.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * Translates service-layer exceptions into consistent HTTP responses with a small JSON body
 * of the form {error, message}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Missing entities map to 404 Not Found.
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(body("Not Found", e.getMessage()));
    }

    /**
     * Illegal lifecycle transitions and bad arguments map to 400 Bad Request.
     */
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(body("Bad Request", e.getMessage()));
    }

    /**
     * Bean-validation failures on request bodies map to 400 Bad Request with field details.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(body("Validation Failed", message));
    }

    /**
     * Any unhandled exception maps to 500 Internal Server Error.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(body("Internal Server Error", e.getMessage()));
    }

    private Map<String, String> body(String error, String message) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("error", error);
        map.put("message", message);
        return map;
    }
}
