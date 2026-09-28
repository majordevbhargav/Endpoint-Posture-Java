package com.endpointposture.hardware;

import com.endpointposture.hardware.dto.HardwareHealthResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Fleet-wide read: newest hardware report per endpoint, in one call. */
@RestController
@RequestMapping("/api/v1/hardware-health")
@Tag(name = "Hardware health fleet", description = "Latest hardware report for every endpoint. Requires a bearer token.")
public class HardwareFleetController {

    private final HardwareHealthService service;

    public HardwareFleetController(HardwareHealthService service) {
        this.service = service;
    }

    @Operation(summary = "Latest hardware report for every endpoint that has one")
    @GetMapping("/latest")
    public List<HardwareHealthResponse> latestForAll() {
        return service.getLatestForAllEndpoints();
    }
}