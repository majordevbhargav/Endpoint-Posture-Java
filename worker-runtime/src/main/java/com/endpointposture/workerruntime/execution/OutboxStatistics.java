package com.endpointposture.workerruntime.execution;

import java.time.Instant;

public record OutboxStatistics(long pending, long permanentFailures, long delivered,
                               long totalRetries, Instant oldestUnresolvedAt, long databaseBytes) { }
