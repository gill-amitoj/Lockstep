package com.lockstep.ledger.web;

import com.lockstep.ledger.logging.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain unit test of the catch-all handler. No endpoint in the app throws an
 * unexpected exception on purpose, so the handler is called directly. It is a
 * real object, not a mock.
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void unexpectedErrorsReturnGeneric500WithoutLeakingInternals() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "trace-500");

        ProblemDetail problem = handler.handleUnexpected(
                new IllegalStateException("password=hunter2 at jdbc:postgresql://db-internal:5432"));

        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getType().toString()).isEqualTo("https://lockstep.example/problems/internal-error");
        assertThat(problem.getDetail()).doesNotContain("hunter2").doesNotContain("postgresql");
        assertThat(problem.getProperties()).containsEntry("correlationId", "trace-500");
    }
}
