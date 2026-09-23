package com.endpointposture.posture.dto;

import com.endpointposture.posture.AssessmentStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The JSON body a PowerShell posture agent sends to {@code POST /api/v1/posture}.
 *
 * <p>Field names match the agent's payload exactly (camelCase), so Jackson
 * binds them with no extra annotations. Unknown extra fields are ignored.</p>
 *
 * @param jobId       the queue job that triggered the run (UUID as text); optional
 * @param macAddress  the endpoint's MAC - the business key used to find or create it (required)
 * @param hostname    reported hostname
 * @param osName      operating system name
 * @param osVersion   operating system version
 * @param ipAddress   primary IP address
 * @param ipAddresses all IPs found on the machine (informational, not stored yet)
 * @param collectedAt when collection began, ISO-8601
 * @param status      the agent's overall result (required)
 * @param hardware    identity of the machine; optional
 * @param checks      individual check results (firewall, ports, applications, ...)
 * @param inventory   raw inventory (ports, apps, processes); accepted but not stored yet
 */
public record PostureReportRequest(
        String jobId,
        @NotBlank String macAddress,
        String hostname,
        String osName,
        String osVersion,
        String ipAddress,
        List<String> ipAddresses,
        Instant collectedAt,
        @NotNull AssessmentStatus status,
        HardwareDto hardware,
        @Valid List<CheckInput> checks,
        InventoryDto inventory
) {

    /** Machine identity, saved onto the endpoint row. */
    public record HardwareDto(String manufacturer, String model, String serialNumber) {}

    /**
     * Raw inventory collected alongside the checks. There are no inventory
     * tables yet, so this is accepted (keeping the agent contract stable)
     * and ignored until those tables exist.
     */
    public record InventoryDto(
            List<Map<String, Object>> listeningPorts,
            List<Map<String, Object>> installedApps,
            List<Map<String, Object>> topProcesses,
            Map<String, Object> resourceUsage
    ) {}
}
