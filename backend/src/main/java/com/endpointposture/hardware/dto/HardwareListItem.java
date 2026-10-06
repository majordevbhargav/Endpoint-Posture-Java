package com.endpointposture.hardware.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the paged hardware list. state: OK (scores are the last good run;
 * lastAttempt* is set if a newer attempt failed), FAILED (never succeeded; collectedAt and
 * lastAttemptError describe the failure) or NO_REPORT.
 */
public record HardwareListItem(UUID endpointId, String hostname, String macAddress, String state,
                               String manufacturer, String model,
                               Integer cpuScore, Integer memoryScore, Integer storageScore, Integer batteryScore,
                               Integer overallScore, String overallBand, Instant collectedAt,
                               Instant lastAttemptFailedAt, String lastAttemptError, int recommendationCount) {}