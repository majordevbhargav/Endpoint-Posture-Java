package com.endpointposture.hardware;

import com.endpointposture.hardware.dto.HardwareHealthResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Read side: an endpoint's hardware-health history. */
@RestController
@RequestMapping("/api/v1/endpoints/{id}/hardware-health")
@Tag(name = "Hardware health history", description = "Read an endpoint's hardware-health reports. Requires a bearer token.")
public class HardwareQueryController {

    private final HardwareHealthService service;

    public HardwareQueryController(HardwareHealthService service) {
        this.service = service;
    }

    @Operation(summary = "Hardware health history for an endpoint, newest first")
    @GetMapping
    public List<HardwareHealthResponse> history(@PathVariable UUID id) {
        return service.getHistoryForEndpoint(id);
    }

    @Operation(summary = "Latest hardware health report for an endpoint")
    @GetMapping("/latest")
    public HardwareHealthResponse latest(@PathVariable UUID id) {
        return service.getLatestForEndpoint(id);
    }
}