package com.endpointposture.hardware.dto;

import java.util.List;
import java.util.Map;

/**
 * The JSON body {@code hardware_health_agent.ps1} POSTs to
 * {@code POST /api/v1/hardware-health}. Field names match the agent's
 * existing payload exactly (snake_case top-level keys aren't used here —
 * Jackson binds camelCase against the agent's JSON via the property
 * names below, matching what {@code ConvertTo-Json} on the PowerShell
 * side already produces for these nested objects).
 *
 * <p>{@code endpoint.mac} is required; that check happens in
 * {@link com.endpointposture.hardware.HardwareIngestService} rather than
 * via a bean-validation annotation, since {@code endpoint} being present
 * but {@code mac} being blank inside it needs a clearer error message
 * than the generic validation framework produces for nested objects.</p>
 *
 * @param jobId                    the triggering job's UUID as text; optional
 * @param endpoint                 identity block (manufacturer/model/serial/bios/mac/hostname/ip)
 * @param cpuMemory                {@code cpu_memory} — cpu.LoadPercentage, memory.UsedPercent
 * @param storage                  {@code storage.physical_disks}
 * @param battery                  {@code battery.battery_static}
 * @param hardwareEvents           {@code hardware_events} — lookback_days, event_count
 * @param warranty                 {@code warranty} — status, days_remaining
 * @param proactiveRecommendations {@code proactive_recommendations}
 */
public record HardwareReportRequest(
        String jobId,
        EndpointDto endpoint,
        Map<String, Object> cpuMemory,
        Map<String, Object> storage,
        Map<String, Object> battery,
        Map<String, Object> hardwareEvents,
        Map<String, Object> warranty,
        List<Map<String, Object>> proactiveRecommendations
) {
    public record EndpointDto(
            String manufacturer,
            String model,
            String serialNumber,
            String biosVersion,
            String mac,
            String hostname,
            String ip
    ) {}
}