package com.lockstep.ledger.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request a correlation id and puts it in the logging MDC, so every
 * log line written while handling the request carries it (the structured JSON
 * logs include MDC values automatically). It is also returned in the
 * X-Correlation-Id response header, added to every problem+json body, and
 * stored on audit records, so a customer complaint, an error response, the
 * logs and the audit trail can all be joined on one id.
 *
 * Runs first (HIGHEST_PRECEDENCE), before Spring Security, so even 401
 * responses have an id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /**
     * A caller-supplied id is only reused if it is short and plain. Anything else
     * (newlines, huge strings) could forge or flood log lines, so it is replaced.
     */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String correlationId = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            // One access-log line per request, as structured key-value pairs.
            log.atInfo()
                    .addKeyValue("http.method", request.getMethod())
                    .addKeyValue("http.path", request.getRequestURI())
                    .addKeyValue("http.status", response.getStatus())
                    .addKeyValue("durationMs", (System.nanoTime() - startNanos) / 1_000_000)
                    .log("Request completed");
            // Threads are reused across requests: always clear, or the next request inherits this id.
            MDC.remove(MDC_KEY);
        }
    }
}
