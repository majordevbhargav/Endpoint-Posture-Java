package com.endpointposture.ise;

import com.endpointposture.session.IseLinkHealth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of whether the ISE session poll is currently working. */
@RestController
@RequestMapping("/api/v1/ise")
@Tag(name = "ISE status", description = "Whether ISE is currently reachable. Requires a bearer token.")
public class IseStatusController {

    private final IseLinkHealth health;

    public IseStatusController(IseLinkHealth health) {
        this.health = health;
    }

    @Operation(summary = "ISE session-poll health",
            description = "reachable=false means session state is frozen at its last known values.")
    @GetMapping("/status")
    public IseLinkHealth.Status status() {
        return health.status();
    }
}