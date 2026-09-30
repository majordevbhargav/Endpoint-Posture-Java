package com.endpointposture.ise;

import com.endpointposture.ise.config.IseProperties;
import com.endpointposture.session.IseLinkHealth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Read-only view of whether the ISE session poll is currently working, plus the settings the UI displays. */
@RestController
@RequestMapping("/api/v1/ise")
@Tag(name = "ISE status", description = "Whether ISE is currently reachable. Requires a bearer token.")
public class IseStatusController {

    /**
     * @param reachable           whether the session poll is working
     * @param lastSuccessAt       last successful poll, or {@code null}
     * @param lastError           latest poll failure reason, or {@code null}
     * @param pollIntervalSeconds how often ISE sessions are polled
     * @param enforcementMode     {@code ATTRIBUTE} or {@code ANC}
     */
    public record IseStatusResponse(boolean reachable, Instant lastSuccessAt, String lastError,
                                    long pollIntervalSeconds, String enforcementMode) {}

    private final IseLinkHealth health;
    private final IseProperties props;

    public IseStatusController(IseLinkHealth health, IseProperties props) {
        this.health = health;
        this.props = props;
    }

    @Operation(summary = "ISE session-poll health",
            description = "reachable=false means session state is frozen at its last known values.")
    @GetMapping("/status")
    public IseStatusResponse status() {
        IseLinkHealth.Status s = health.status();
        return new IseStatusResponse(
                s.reachable(), s.lastSuccessAt(), s.lastError(),
                Math.max(1, props.getSessionPollIntervalMs() / 1000),
                props.getEnforcementMode().name());
    }
}