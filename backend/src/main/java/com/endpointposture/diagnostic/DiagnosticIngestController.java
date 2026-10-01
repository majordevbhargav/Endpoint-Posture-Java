package com.endpointposture.diagnostic;

import com.endpointposture.diagnostic.dto.DiagnosticReportRequest;
import com.endpointposture.diagnostic.dto.DiagnosticResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Write side: where {@code diagnostic_agent.ps1} delivers its report. Separate
 * class from the read controller, same split as posture and hardware.
 * Never contacts Cisco ISE.
 */
@RestController
@RequestMapping("/api/v1/diagnostics")
@Tag(name = "Diagnostics ingestion", description = "Where the diagnostic agent submits results. Accepts an admin JWT or the agent API key header.")
public class DiagnosticIngestController {

    private final DiagnosticService service;

    public DiagnosticIngestController(DiagnosticService service) {
        this.service = service;
    }

    @Operation(summary = "Submit a diagnostic report",
            description = "Called by diagnostic_agent.ps1. Scores the probe results and stores the raw report as JSONB.")
    @PostMapping
    public ResponseEntity<DiagnosticResponse> ingest(@RequestBody DiagnosticReportRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.ingest(req));
    }

    /** JSON body so the caller sees the reason, not just "Bad Request". */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}