package oak.server.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;

/**
 * Maps application exceptions to clean JSON error responses with correct HTTP statuses,
 * so failures return structured bodies (and bypass the default {@code /error} dispatch).
 *
 * Every mapped failure also lands in the server log (logging round, 2026-10-06): a 403/404/409
 * raised in a service was previously invisible on disk — twice a silent server-side rejection
 * was blamed on the client because "the server log is clean". 4xx map to a one-line WARN with
 * the method/URI; the catch-all logs the full stack at ERROR.
 *
 * Extends {@link ResponseEntityExceptionHandler} so Spring's standard MVC exceptions
 * (405/406/415/…) keep their built-in statuses and bodies — without it, the broad
 * {@code Exception} catch-all below would swallow them and turn a 405 into a 500. The
 * validation case therefore OVERRIDES the parent hook instead of declaring its own
 * @ExceptionHandler (a second mapping for MethodArgumentNotValidException is an
 * AmbiguousMappingException at startup).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Object> handleResponseStatus(ResponseStatusException ex,
                                                       HttpServletRequest request) {
        HttpStatusCode status = ex.getStatusCode();
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String reason = resolved != null ? resolved.getReasonPhrase() : "Error";
        log.warn("[api] {} {} -> {} {}: {}", request.getMethod(), request.getRequestURI(),
                status.value(), reason, ex.getReason());
        return response(status.value(), reason, ex.getReason());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("Validation failed");
        ServletWebRequest servletRequest = (ServletWebRequest) request;
        log.warn("[api] {} {} -> {} {}: {}", servletRequest.getHttpMethod(),
                servletRequest.getRequest().getRequestURI(), status.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(), message);
        return response(HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.getReasonPhrase(), message);
    }

    /** The catch-all: unexpected exceptions get the same JSON contract (instead of the default
     *  {@code /error} dispatch) and a full stack trace in the log at ERROR. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnhandled(Exception ex, HttpServletRequest request) {
        log.error("[api] {} {} -> unhandled exception", request.getMethod(), request.getRequestURI(), ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(), "Internal server error");
    }

    private static ResponseEntity<Object> response(int status, String error, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "status", status,
                "error", error,
                "message", message == null ? "" : message));
    }
}
