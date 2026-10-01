package com.endpointposture.diagnostic.dto;

import com.endpointposture.diagnostic.EndpointDiagnostic;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API view of an {@link EndpointDiagnostic}.
 *
 * @param score      0-100, or {@code null} when nothing was measured (never zero)
 * @param band       HEALTHY / WARNING / DEGRADED / CRITICAL, or {@code null}
 * @param deductions why points were lost
 * @param results    the raw measurements (gateway, dns, internet, tcp443, traceroute)
 */
public record DiagnosticResponse(
        UUID id,
        UUID endpointId,
        UUID jobId,
        EndpointDiagnostic.Status status,
        Integer score,
        EndpointDiagnostic.Band band,
        List<Map<String, Object>> deductions,
        Map<String, Object> results,
        String errorMessage,
        Instant collectedAt
) {}