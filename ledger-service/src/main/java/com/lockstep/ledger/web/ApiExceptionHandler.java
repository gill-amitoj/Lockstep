package com.lockstep.ledger.web;

import com.lockstep.ledger.logging.CorrelationIdFilter;
import com.lockstep.ledger.service.AuthenticationFailedException;
import com.lockstep.ledger.service.ConflictException;
import com.lockstep.ledger.service.IdempotencyKeyReusedException;
import com.lockstep.ledger.service.InsufficientFundsException;
import com.lockstep.ledger.service.InvalidRequestException;
import com.lockstep.ledger.service.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;

/**
 * Every error leaving a controller becomes RFC 9457 problem+json, with the
 * request's correlation id in it.
 *
 *  - Our domain exceptions map to 400 / 401 / 404 / 409 / 422 below.
 *  - Spring MVC's own exceptions (malformed JSON, wrong method, unknown URL, ...)
 *    are handled by the ResponseEntityExceptionHandler superclass, which
 *    already produces ProblemDetail bodies; createResponseEntity adds the id.
 *  - Anything unexpected becomes a 500 that reveals nothing internal.
 *  - 401/403 from Spring Security happen before controllers run; see
 *    SecurityProblemHandler for those.
 *
 * The services throw plain exceptions and know nothing about HTTP; the mapping
 * to status codes lives only here.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail handleNotFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Resource not found", e.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail handleInvalidRequest(InvalidRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", e.getMessage());
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    ProblemDetail handleAuthenticationFailed(AuthenticationFailedException e) {
        return problem(HttpStatus.UNAUTHORIZED, "authentication-failed", "Authentication failed", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail handleConflict(ConflictException e) {
        return problem(HttpStatus.CONFLICT, "conflict", "Conflict", e.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail handleInsufficientFunds(InsufficientFundsException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "insufficient-funds", "Insufficient funds", e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail handleIdempotencyKeyReused(IdempotencyKeyReusedException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused",
                "Idempotency key reused", e.getMessage());
    }

    /** A database constraint stopped the write (e.g. two sign-ups racing for one username). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("Constraint violation", e);
        return problem(HttpStatus.CONFLICT, "conflict", "Conflict",
                "The request conflicts with existing data");
    }

    /** Lock wait gave up or the database broke a deadlock. Nothing was written, so retrying is safe. */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ProblemDetail handleLockFailure(PessimisticLockingFailureException e) {
        log.warn("Lock failure", e);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "temporarily-unavailable", "Temporarily unavailable",
                "The request could not be completed right now; retry with the same Idempotency-Key");
    }

    /**
     * Last resort. The client gets a generic message: exception messages and stack
     * traces can leak table names, SQL or internal state. The full error goes to
     * the log, and the correlation id in the response lets support find it.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal server error",
                "An unexpected error occurred. Quote the correlationId when contacting support.");
    }

    /** Adds the correlation id to the problem bodies Spring MVC builds for its own exceptions. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            addCorrelationId(problem);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        // "type" is a stable identifier clients can switch on; "title" is for humans.
        problem.setType(URI.create("https://lockstep.example/problems/" + type));
        problem.setTitle(title);
        addCorrelationId(problem);
        return problem;
    }

    private static void addCorrelationId(ProblemDetail problem) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
    }
}
