package com.endpointposture.posture;

import com.endpointposture.posture.dto.AssessmentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
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
     * @return {@code 200} with the most recent assessment, or {@code 204 No Content}
     *         if the endpoint has never been assessed (same convention as hardware health)
     */
    @Operation(summary = "Latest assessment for an endpoint",
            description = "Returns 204 No Content when the endpoint has never been assessed.")
    @GetMapping("/latest")
    public ResponseEntity<AssessmentResponse> latest(@PathVariable UUID id) {
        return service.findLatestForEndpoint(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}