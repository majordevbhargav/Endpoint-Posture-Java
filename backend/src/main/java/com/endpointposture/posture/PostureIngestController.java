package com.endpointposture.posture;

import com.endpointposture.posture.dto.AssessmentResponse;
import com.endpointposture.posture.dto.PostureReportRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The write side of posture: where the PowerShell agent delivers its report.
 *
 * <p>Deliberately its own class, separate from the read endpoints and from
 * any ISE action controller, so "ingest posture" can never fall through
 * into "call ISE" inside one method.</p>
 */
@RestController
@RequestMapping("/api/v1/posture")
@Tag(name = "Posture ingestion", description = "Where posture agents submit results. Accepts an admin JWT or the agent API key header.")
public class PostureIngestController {

    private final PostureIngestService service;

    public PostureIngestController(PostureIngestService service) {
        this.service = service;
    }

    /**
     * Stores one posture report.
     *
     * @param req the agent's report; validated ({@code 400} if MAC or status is missing)
     * @return {@code 201 Created} with the saved assessment
     */
    @Operation(
            summary = "Submit a posture report",
            description = "Called by the PowerShell posture agent. Creates or updates the endpoint by MAC, "
                    + "then appends one assessment and its check results. Never contacts Cisco ISE."
    )
    @PostMapping
    public ResponseEntity<AssessmentResponse> ingest(@Valid @RequestBody PostureReportRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.ingest(req));
    }
}
