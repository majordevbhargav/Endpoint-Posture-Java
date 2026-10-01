package com.endpointposture.indicator.dto;

import com.endpointposture.indicator.EndpointSecurityIndicator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** API view. The raw samples stay in the database; only findings and summary are returned. */
public record SecurityIndicatorResponse(
        UUID id, UUID endpointId, UUID jobId,
        EndpointSecurityIndicator.Status status,
        EndpointSecurityIndicator.RiskLevel riskLevel,
        List<Map<String, Object>> findings,
        Map<String, Object> summary,
        String errorMessage,
        Instant collectedAt) {}