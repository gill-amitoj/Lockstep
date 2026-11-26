package com.lockstep.ledger.web;

import com.lockstep.ledger.reconciliation.ReconciliationReport;
import com.lockstep.ledger.reconciliation.ReconciliationRun;
import com.lockstep.ledger.reconciliation.ReconciliationService;
import com.lockstep.ledger.service.AuditService;
import com.lockstep.ledger.service.ConflictException;
import com.lockstep.ledger.service.InvalidRequestException;
import com.lockstep.ledger.service.NotFoundException;
import com.lockstep.ledger.web.dto.AuditEventResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/** ADMIN only. SecurityConfig rejects every other role for /admin/** before these methods run. */
@Tag(name = "Admin", description = "ADMIN role only")
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final int MAX_LIMIT = 500;

    private final AuditService auditService;
    private final ReconciliationService reconciliationService;

    public AdminController(AuditService auditService, ReconciliationService reconciliationService) {
        this.auditService = auditService;
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/audit-events")
    public List<AuditEventResponse> auditEvents(@RequestParam(defaultValue = "100") int limit) {
        return auditService.recent(checkLimit(limit)).stream()
                .map(AuditEventResponse::from)
                .toList();
    }

    /** Runs reconciliation now, instead of waiting for the schedule. */
    @PostMapping("/reconciliation-runs")
    public ResponseEntity<ReconciliationReport> runReconciliation(@AuthenticationPrincipal Jwt jwt) {
        ReconciliationReport report = reconciliationService.run(jwt.getSubject())
                .orElseThrow(() -> new ConflictException("A reconciliation run is already in progress"));
        return ResponseEntity
                .created(URI.create("/admin/reconciliation-runs/" + report.run().id()))
                .body(report);
    }

    @GetMapping("/reconciliation-runs")
    public List<ReconciliationRun> recentRuns(@RequestParam(defaultValue = "20") int limit) {
        return reconciliationService.recentRuns(checkLimit(limit));
    }

    @GetMapping("/reconciliation-runs/{id}")
    public ReconciliationReport run(@PathVariable long id) {
        return reconciliationService.findRun(id)
                .orElseThrow(() -> new NotFoundException("Reconciliation run " + id + " not found"));
    }

    private static int checkLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidRequestException("limit must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }
}
