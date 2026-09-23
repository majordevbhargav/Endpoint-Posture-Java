package com.endpointposture.hardware;

import com.endpointposture.hardware.dto.HardwareHealthResponse;
import com.endpointposture.hardware.dto.HardwareReportRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Write side: where {@code hardware_health_agent.ps1} delivers its
 * report. Separate class from the read controller below, same reasoning
 * as posture's ingest/query split.
 */
@RestController
@RequestMapping("/api/v1/hardware-health")
@Tag(name = "Hardware health ingestion", description = "Where the hardware-health agent submits results.")
public class HardwareIngestController {

    private final HardwareIngestService service;

    public HardwareIngestController(HardwareIngestService service) {
        this.service = service;
    }

    @Operation(summary = "Submit a hardware health report",
            description = "Called by hardware_health_agent.ps1. Scores CPU/memory/storage/battery, "
                    + "computes an overall band, and stores the raw report as JSONB.")
    @PostMapping
    public ResponseEntity<HardwareHealthResponse> ingest(@RequestBody HardwareReportRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.ingest(req));
    }
}