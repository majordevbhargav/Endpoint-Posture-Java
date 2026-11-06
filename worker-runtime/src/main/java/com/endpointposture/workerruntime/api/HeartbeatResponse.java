package com.endpointposture.workerruntime.api;

import java.time.Instant;

public record HeartbeatResponse(String status, Instant lastSeenAt,
                                long heartbeatIntervalSeconds, long staleAfterSeconds) {}