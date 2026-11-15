package in.ilovemyfiles.common;

import in.ilovemyfiles.share.PasswordRequiredException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(AppException.class)
    public ResponseEntity<Map<String, Object>> handleAppException(AppException ex) {
        return error(ex.getStatusCode(), ex.getMessage());
    }

    @ExceptionHandler(PasswordRequiredException.class)
    public ResponseEntity<Map<String, Object>> handlePasswordRequired(PasswordRequiredException ex) {
        return error(HttpStatus.FORBIDDEN.value(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }
        ResponseEntity<Map<String, Object>> response = error(HttpStatus.BAD_REQUEST.value(), "Validation failed");
        response.getBody().put("errors", errors);
        return response;
    }

    /** A body that isn't valid JSON, or a path value of the wrong type (e.g. not a UUID). */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> handleMalformedRequest(Exception ex) {
        return error(HttpStatus.BAD_REQUEST.value(), "Malformed request");
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException ex) {
        return unexpected(ex);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        return unexpected(ex);
    }

    private ResponseEntity<Map<String, Object>> unexpected(Exception ex) {
        // Spring MVC's own errors (method not allowed, no such route, missing
        // parameter, unsupported media type...) carry their status: they are
        // client mistakes, and used to come back from here as 500s.
        if (ex instanceof ErrorResponse errorResponse) {
            int status = errorResponse.getStatusCode().value();
            String detail = errorResponse.getBody().getDetail();
            return error(status, detail != null ? detail : HttpStatus.valueOf(status).getReasonPhrase());
        }
        // The client only sees a generic message, so the cause must reach the logs.
        log.error("Unhandled exception", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal server error");
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("message", message);
        body.put("status", status);
        return ResponseEntity.status(status).body(body);
    }
}
