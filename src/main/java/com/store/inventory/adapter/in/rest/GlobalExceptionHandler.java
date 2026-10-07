package com.store.inventory.adapter.in.rest;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import java.net.URI;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Traduce excepciones de dominio y del framework a {@link ProblemDetail} (RFC 9457) con un {@code errorCode} estable.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String MDC_TRACE = "traceId";

    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ProblemDetail> invalidRequest(Exception ex) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request", ex.getMessage(), ex, false);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException ex) {
        String detail = messageForUnreadable(ex);
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request", detail, ex, false);
    }

    @ExceptionHandler(OrderLimitExceededException.class)
    ResponseEntity<ProblemDetail> orderLimit(OrderLimitExceededException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "ORDER_LIMIT_EXCEEDED", "Order limit exceeded",
                ex.getMessage(), ex, false);
    }

    @ExceptionHandler(InsufficientStockException.class)
    ResponseEntity<ProblemDetail> insufficient(InsufficientStockException ex) {
        return problem(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", "Insufficient stock", ex.getMessage(), ex, false);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> noActiveReservation(IllegalStateException ex) {
        return problem(HttpStatus.CONFLICT, "NO_ACTIVE_RESERVATION", "No active reservation",
                ex.getMessage(), ex, false);
    }

    @ExceptionHandler({PessimisticLockingFailureException.class, QueryTimeoutException.class})
    ResponseEntity<ProblemDetail> temporarilyUnavailable(Exception ex) {
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.SERVICE_UNAVAILABLE, "TEMPORARILY_UNAVAILABLE",
                "Temporarily unavailable", "The inventory is busy; retry shortly.", ex, false);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(response.getBody());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NoResourceFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found", "No such resource.", ex, false);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method not allowed",
                "HTTP method " + ex.getMethod() + " is not supported for this resource.", ex, false);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> unsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Unsupported media type",
                "Content type is not supported; use application/json.", ex, false);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> internal(Exception ex) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal error",
                "An unexpected error occurred.", ex, true);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String errorCode, String title, String detail,
            Exception ex, boolean logAsError) {
        String traceId = UUID.randomUUID().toString();
        MDC.put(MDC_TRACE, traceId);
        try {
            if (logAsError) {
                LOG.error("Unhandled error traceId={} errorCode={}", traceId, errorCode, ex);
            }
            ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
            body.setType(URI.create("urn:problem:" + kebab(errorCode)));
            body.setTitle(title);
            body.setProperty("errorCode", errorCode);
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
        } finally {
            MDC.remove(MDC_TRACE);
        }
    }

    private static String kebab(String errorCode) {
        return errorCode.toLowerCase().replace('_', '-');
    }

    private static String messageForUnreadable(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getMostSpecificCause();
        String target = cause.getClass().getName();
        if (target.contains("InvalidFormat") || (cause.getMessage() != null && cause.getMessage().contains("ProductCategory"))) {
            String values = Arrays.stream(ProductCategory.values()).map(Enum::name).collect(Collectors.joining(", "));
            return "Unknown category. Valid values: " + values;
        }
        return "Malformed JSON request";
    }
}
