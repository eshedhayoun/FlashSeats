package com.flashseats.shared.error;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The one exception handler for the whole application (ADR-033).
 *
 * <p>Global standards §1 asked for a {@code @RestControllerAdvice} per module, reasoning that a
 * single global advice would have to import every module's exception types and so break the
 * boundary Modulith enforces. Because every module exception extends {@link FlashSeatsException} and
 * carries its own {@link ErrorCode}, this handler catches the base type and imports nothing
 * module-specific — the constraint is satisfied with one class instead of seven.
 *
 * <p>Runs at {@link Ordered#LOWEST_PRECEDENCE} so a module may still add its own advice later
 * without being shadowed.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Every deliberate business failure in every module arrives here. */
    @ExceptionHandler(FlashSeatsException.class)
    public ProblemDetail onFlashSeats(FlashSeatsException ex) {
        if (ex.code().status().is5xxServerError()) {
            log.error("{}: {}", ex.code(), ex.getMessage(), ex);
        } else {
            log.debug("{}: {}", ex.code(), ex.getMessage());
        }
        return ProblemDetails.from(ex);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        return ProblemDetails.of(
                ErrorCode.VALIDATION_FAILED,
                "One or more fields are invalid.",
                Map.of("violations", violations));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail onUnreadableBody(HttpMessageNotReadableException ex) {
        return ProblemDetails.of(ErrorCode.VALIDATION_FAILED, "Request body could not be read.");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail onMissingHeader(MissingRequestHeaderException ex) {
        return ProblemDetails.of(
                ErrorCode.VALIDATION_FAILED, "Missing required header: " + ex.getHeaderName());
    }

    /**
     * The backstop. Returns a bare {@code 500} carrying only a {@code traceId} — the stack trace
     * goes to the log, never to the client.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnhandled(Exception ex) {
        log.error("Unhandled exception", ex);
        return ProblemDetails.of(
                ErrorCode.INTERNAL_ERROR, "Something went wrong. Quote the traceId to support.");
    }
}
