package com.endpointposture.workerruntime.execution;

import java.time.Instant;
import java.util.UUID;

/** Authenticated identity and exact payload bytes stored with a completed attempt. */
public record PersistedJobResult(UUID workerId, String shard, CompletedJobResult result,
                                 byte[] requestPayload, String payloadSha256,
                                 int retryCount, Instant nextAttemptAt, Instant deliveryDeadline,
                                 int cumulativeLeaseExtensionSeconds) {
    public PersistedJobResult {
        requestPayload = requestPayload.clone();
    }
    @Override public byte[] requestPayload() { return requestPayload.clone(); }
}
