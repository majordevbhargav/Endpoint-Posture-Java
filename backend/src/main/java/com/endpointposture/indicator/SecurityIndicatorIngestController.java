package com.endpointposture.indicator;

import com.endpointposture.indicator.dto.SecurityIndicatorResponse;
import com.endpointposture.indicator.dto.SecurityReportRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Write side: receives security-indicator reports. Never contacts ISE. */
@RestController
@RequestMapping("/api/v1/security-indicators")
@Tag(name = "Security indicators ingestion",
     description = "Accepts an admin JWT or the agent API key header.")
public class SecurityIndicatorIngestController {

    private final SecurityIndicatorService service;

    public SecurityIndicatorIngestController(SecurityIndicatorService service) {
        this.service = service;
    }

    @Operation(summary = "Submit a security-indicator report")
    @PostMapping
    public ResponseEntity<SecurityIndicatorResponse> ingest(
            @RequestBody SecurityReportRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.ingest(req));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badInput(
            IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("message", e.getMessage()));
    }
}