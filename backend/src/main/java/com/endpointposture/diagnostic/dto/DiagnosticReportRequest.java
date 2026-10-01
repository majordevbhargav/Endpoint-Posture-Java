package com.endpointposture.diagnostic.dto;

import java.util.Map;

/**
 * The JSON body {@code diagnostic_agent.ps1} POSTs to {@code POST /api/v1/diagnostics}.
 * Field names are camelCase and match the agent's payload exactly.
 *
 * <p>{@code endpoint.mac} is required: the worker always hands the agent the
 * endpoint's own MAC, so even a report that says the endpoint could not be
 * reached over WinRM can be attached to the right device.</p>
 *
 * @param jobId      the triggering job's UUID as text; optional
 * @param endpoint   identity block (mac required; hostname and ip optional)
 * @param status     {@code OK} or {@code WINRM_UNAVAILABLE}
 * @param detail     why, when the endpoint could not be probed
 * @param gateway    {address, reachable, avgMs, lossPct}
 * @param dns        {target, resolved, ms, error}
 * @param internet   {address, reachable, avgMs, lossPct}
 * @param tcp443     {target, port, connected, ms}
 * @param traceroute {target, completed, hops[]}
 */
public record DiagnosticReportRequest(
        String jobId,
        EndpointDto endpoint,
        String status,
        String detail,
        Map<String, Object> gateway,
        Map<String, Object> dns,
        Map<String, Object> internet,
        Map<String, Object> tcp443,
        Map<String, Object> traceroute
) {
    public record EndpointDto(String mac, String hostname, String ip) {}
}