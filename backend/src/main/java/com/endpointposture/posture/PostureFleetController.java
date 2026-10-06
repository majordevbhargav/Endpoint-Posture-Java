package com.endpointposture.posture;

import com.endpointposture.posture.dto.AssessmentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Fleet-wide posture reads. */
@RestController
@RequestMapping("/api/v1/posture")
@Tag(name = "Posture fleet", description = "Latest assessments. Requires a bearer token.")
public class PostureFleetController {

    private static final int MAX_BATCH = 100;

    private final AssessmentService service;

    public PostureFleetController(AssessmentService service) {
        this.service = service;
    }

    @Operation(summary = "Latest assessment for every endpoint (unbounded; small fleets only)")
    @GetMapping("/latest")
    public List<AssessmentResponse> latestForAll() {
        return service.getLatestForAllEndpoints();
    }

    @Operation(summary = "Latest assessment, with checks, for up to 100 endpoint ids")
    @GetMapping("/latest/batch")
    public List<AssessmentResponse> latestBatch(@RequestParam List<UUID> ids) {
        if (ids.size() > MAX_BATCH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most " + MAX_BATCH + " ids per call");
        }
        return service.getLatestForEndpoints(ids);
    }
}