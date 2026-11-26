package com.settle.ledger.web;

import com.settle.ledger.service.InsufficientFundsException;
import com.settle.ledger.service.InvalidRequestException;
import com.settle.ledger.service.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Turns our domain exceptions into RFC 9457 problem+json responses.
 * Returning a ProblemDetail makes Spring set Content-Type: application/problem+json.
 *
 * The services throw plain exceptions and know nothing about HTTP; the mapping
 * to status codes lives only here. Phase 4 extends this class (catch-all 500,
 * security errors, correlation id).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail handleNotFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Resource not found", e.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail handleInvalidRequest(InvalidRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", e.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail handleInsufficientFunds(InsufficientFundsException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "insufficient-funds", "Insufficient funds", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        // "type" is a stable identifier clients can switch on; "title" is for humans.
        problem.setType(URI.create("https://settle.example/problems/" + type));
        problem.setTitle(title);
        return problem;
    }
}
