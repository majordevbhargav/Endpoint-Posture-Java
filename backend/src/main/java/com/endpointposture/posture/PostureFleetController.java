package com.endpointposture.posture;

import com.endpointposture.posture.dto.AssessmentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Fleet-wide read: newest assessment per endpoint, in one call. */
@RestController
@RequestMapping("/api/v1/posture")
@Tag(name = "Posture fleet", description = "Latest assessment for every endpoint. Requires a bearer token.")
public class PostureFleetController {

    private final AssessmentService service;

    public PostureFleetController(AssessmentService service) {
        this.service = service;
    }

    @Operation(summary = "Latest assessment for every endpoint that has one")
    @GetMapping("/latest")
    public List<AssessmentResponse> latestForAll() {
        return service.getLatestForAllEndpoints();
    }
}