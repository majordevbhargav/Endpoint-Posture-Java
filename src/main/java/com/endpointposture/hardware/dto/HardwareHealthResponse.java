package com.endpointposture.hardware.dto;

import com.endpointposture.hardware.HardwareBand;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API view of a {@link com.endpointposture.hardware.HardwareHealthReport}
 * with its recommendations attached.
 *
 * <p>When {@code succeeded} is {@code false} (a collection/scoring
 * failure), the score fields and {@code overallBand} are {@code null}
 * and {@code errorMessage} explains why — this is the hardware
 * equivalent of an {@code ERROR} posture assessment.</p>
 */
public record HardwareHealthResponse(
        UUID id,
        UUID endpointId,
        UUID jobId,
        String manufacturer,
        String model,
        String serialNumber,
        String biosVersion,
        Integer cpuScore,
        Integer memoryScore,
        Integer storageScore,
        Integer batteryScore,
        Integer overallScore,
        HardwareBand overallBand,
        Integer hardwareEventCount,
        String warrantyStatus,
        Integer warrantyDaysRemaining,
        Instant collectedAt,
        boolean succeeded,
        String errorMessage,
        List<RecommendationDto> recommendations
) {
    public record RecommendationDto(String priority, String area, String action) {}
}