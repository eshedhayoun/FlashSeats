package com.flashseats.shared.error;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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
@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

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
     * Malformed requests that Spring MVC rejects before a handler ever runs.
     *
     * <p><strong>These must be listed explicitly, and that is not a formality.</strong>
     * {@code ExceptionHandlerExceptionResolver} runs <em>before</em>
     * {@code DefaultHandlerExceptionResolver}, so the {@code Exception.class} backstop below matches
     * first and would answer every one of them with {@code 500 INTERNAL_ERROR} — a client error
     * reported as a server fault, with no registry {@code code} to branch on and an
     * {@code ERROR}-level log line for every mistyped query string. A missing {@code eventId} on
     * {@code POST /queue/admit} did exactly that.
     *
     * <p>Global standards §1: {@code 400} is malformed input, {@code 500} is never a client error,
     * and every problem carries a {@code code}.
     */
    @ExceptionHandler({
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class,
        MissingPathVariableException.class
    })
    public ProblemDetail onMalformedRequest(Exception ex) {
        return ProblemDetails.of(ErrorCode.VALIDATION_FAILED, detailFor(ex));
    }

    /**
     * Wrong method or wrong content type. Kept apart from the {@code 400}s because the status is
     * part of the answer — {@code 405} and {@code 415} tell a client something {@code 400} does not.
     */
    @ExceptionHandler({
        HttpRequestMethodNotSupportedException.class,
        HttpMediaTypeNotSupportedException.class
    })
    public ResponseEntity<ProblemDetail> onUnsupportedRequest(ErrorResponse ex) {
        HttpStatusCode status = ex.getStatusCode();
        ProblemDetail problem =
                ProblemDetails.of(ErrorCode.VALIDATION_FAILED, ex.getBody().getDetail());
        problem.setStatus(status.value());
        return ResponseEntity.status(status).body(problem);
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

    /** Names the offending parameter without echoing whatever the client sent. */
    private String detailFor(Exception ex) {
        if (ex instanceof MissingServletRequestParameterException missing) {
            return "Missing required parameter: " + missing.getParameterName();
        }
        if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            return "Parameter '" + mismatch.getName() + "' is not in the expected format.";
        }
        return "The request could not be understood.";
    }
}
