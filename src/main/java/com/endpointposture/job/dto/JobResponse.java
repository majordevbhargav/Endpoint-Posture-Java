package com.endpointposture.job.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * API view of a {@link com.endpointposture.job.PostureJob}.
 *
 * @param id           the job's identifier
 * @param endpointId   the endpoint it runs against
 * @param macAddress   that endpoint's MAC, for readability
 * @param jobType      {@code POSTURE_CHECK} or {@code HARDWARE_CHECK}
 * @param status       {@code QUEUED}, {@code RUNNING}, {@code COMPLETE} or {@code FAILED}
 * @param priority     higher values are claimed first
 * @param attemptCount how many times a worker has started it
 * @param maxAttempts  attempts allowed before it ends in {@code FAILED}
 * @param errorMessage reason for the latest failure, if any
 * @param createdAt    when it was enqueued
 * @param startedAt    when a worker last started it
 * @param completedAt  when it finished or finally failed
 */
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
