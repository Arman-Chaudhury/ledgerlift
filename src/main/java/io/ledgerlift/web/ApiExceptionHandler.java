package io.ledgerlift.web;

import io.ledgerlift.imports.BatchNotFoundException;
import io.ledgerlift.template.TemplateException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    public record ApiError(int status, String error, String message, Instant timestamp) {}

    @ExceptionHandler(BatchNotFoundException.class)
    public ResponseEntity<ApiError> notFound(BatchNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({TemplateException.class, IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiError> badRequest(RuntimeException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static ResponseEntity<ApiError> body(HttpStatus s, String msg) {
        return ResponseEntity.status(s).body(new ApiError(s.value(), s.getReasonPhrase(), msg, Instant.now()));
    }

    static Map<String, Object> map(ApiError e) {
        return Map.of("status", e.status(), "error", e.error(), "message", e.message());
    }
}
