package com.endpointposture.system;

import com.endpointposture.system.SystemHealthDtos.SystemHealth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only platform health for the System Health page and the sidebar. Requires a bearer token. */
@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "System health", description = "Database, ISE poll, job queue and worker pool status.")
public class SystemHealthController {

    private final SystemHealthService service;

    public SystemHealthController(SystemHealthService service) {
        this.service = service;
    }

    @Operation(summary = "Platform health snapshot",
            description = "Always returns 200 with a status of UP, DEGRADED or DOWN; the reasons are in warnings.")
    @GetMapping("/health")
    public SystemHealth health() {
        return service.snapshot();
    }
}