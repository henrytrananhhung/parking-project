package com.parking.exception;

import java.util.stream.Collectors;

import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps errors to RFC 9457 ProblemDetail with a stable {@code code} property.
 * Messages never include request values (passwords, tokens, etc.).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Seconds a client should wait before retrying after a 503. */
    static final int RETRY_AFTER_SECONDS = 1;

    @ExceptionHandler(BusinessException.class)
    ProblemDetail handleBusiness(BusinessException ex) {
        return problem(ex.code());
    }

    /**
     * The database is out of reach for the moment: connection pool exhausted (the 8 PM peak), connection lost,
     * lock or query timeout. That is a temporary server condition, not a bug, so answer 503 + Retry-After
     * instead of 500. The transaction did not commit, except in the rare case of a connection lost during
     * commit; reservations are idempotent, so retrying them is safe either way.
     */
    @ExceptionHandler({ CannotCreateTransactionException.class, TransientDataAccessException.class,
            DataAccessResourceFailureException.class })
    ResponseEntity<ProblemDetail> handleDatabaseUnavailable(Exception ex) {
        // Handled exceptions are no longer logged by the container: keep the signal, without a stack trace
        // per request. The root cause names the condition, e.g. Hikari's pool stats on a timeout.
        log.warn("Database temporarily unavailable, answering 503: {}",
                NestedExceptionUtils.getMostSpecificCause(ex).toString());
        return temporarilyUnavailable();
    }

    /**
     * A bounded wait for a space lock or a reservation permit expired: load shedding at the peak, counted by the
     * {@code parking.space_lock.wait} / {@code parking.db_permit.wait} metrics (outcome=timeout) rather than logged
     * per request. Nothing was written, so retrying is safe.
     */
    @ExceptionHandler(CapacityExceededException.class)
    ResponseEntity<ProblemDetail> handleCapacityExceeded(CapacityExceededException ex) {
        log.debug("Capacity exceeded, answering 503: {}", ex.getMessage());
        return temporarilyUnavailable();
    }

    /**
     * Redis (reservation coordination) is unreachable. Reservations fail closed instead of reaching PostgreSQL
     * uncoordinated; nothing was written, so retrying is safe. Deposits and reads never need Redis.
     */
    @ExceptionHandler(RedisException.class)
    ResponseEntity<ProblemDetail> handleRedisUnavailable(RedisException ex) {
        log.warn("Redis unavailable, answering 503: {}", ex.toString());
        return temporarilyUnavailable();
    }

    private static ResponseEntity<ProblemDetail> temporarilyUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(RETRY_AFTER_SECONDS))
                .body(problem(ErrorCode.TEMPORARILY_UNAVAILABLE));
    }

    private static ProblemDetail problem(ErrorCode code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), code.message());
        problem.setTitle(code.name());
        problem.setProperty("code", code.name());
        return problem;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // Field names + constraint messages only; rejected values are deliberately omitted.
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("VALIDATION_FAILED");
        problem.setProperty("code", "VALIDATION_FAILED");
        return ResponseEntity.badRequest().body(problem);
    }
}
