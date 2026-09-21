package com.endpointposture.posture;

import com.endpointposture.posture.dto.AssessmentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The read side of posture: an endpoint's assessment history.
 * Kept separate from {@link PostureIngestController} on purpose.
 */
@RestController
@RequestMapping("/api/v1/endpoints/{id}/posture")
@Tag(name = "Posture history", description = "Read an endpoint's assessments. Requires a bearer token.")
public class PostureQueryController {

    private final AssessmentService service;

    public PostureQueryController(AssessmentService service) {
        this.service = service;
    }

    /**
     * @param id the endpoint's internal UUID
     * @return every assessment for that endpoint, newest first (empty list if none)
     */
    @Operation(summary = "Assessment history for an endpoint, newest first")
    @GetMapping
    public List<AssessmentResponse> history(@PathVariable UUID id) {
        return service.getHistoryForEndpoint(id);
    }

    /**
     * @param id the endpoint's internal UUID
     * @return the most recent assessment, or {@code 404} if the endpoint has never been assessed
     */
    @Operation(summary = "Latest assessment for an endpoint")
    @GetMapping("/latest")
    public AssessmentResponse latest(@PathVariable UUID id) {
        return service.getLatestForEndpoint(id);
    }
}
