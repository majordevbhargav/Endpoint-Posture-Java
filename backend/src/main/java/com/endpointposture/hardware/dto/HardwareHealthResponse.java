package com.endpointposture.hardware.dto;

import com.endpointposture.hardware.HardwareBand;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API view of a {@link com.endpointposture.hardware.HardwareHealthReport}
 * with its recommendations attached.
 *
 * <p>
 * When {@code succeeded} is {@code false} (a collection/scoring failure
 * and no earlier good run exists), the score fields and {@code overallBand}
 * are {@code null} and {@code errorMessage} explains why.
 * </p>
 *
 * <p>
 * The "latest" endpoints return the newest <em>successful</em> run when
 * one exists. If a newer attempt failed after it, {@code lastAttemptFailedAt}
 * and {@code lastAttemptError} describe that failure so the UI can show a
 * warning next to the last good scores. Both are {@code null} otherwise.
 * The history endpoint always returns raw rows, so they are {@code null}
 * there.
 * </p>
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
        Instant lastAttemptFailedAt,
        String lastAttemptError,
        List<RecommendationDto> recommendations) {
    public record RecommendationDto(String priority, String area, String action) {
    }
}