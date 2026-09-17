package com.endpointposture.job.dto;

import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID id,
        UUID endpointId,
        String macAddress,
        String jobType,
        String status,
        int priority,
        int attemptCount,
        int maxAttempts,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {}