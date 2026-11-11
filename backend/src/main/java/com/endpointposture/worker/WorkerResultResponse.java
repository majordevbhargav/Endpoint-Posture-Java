package com.endpointposture.worker;

import java.time.Instant;
import java.util.UUID;

/**
 * Acknowledgement returned after a worker result has been accepted and durably recorded.
 */
public record WorkerResultResponse(
        boolean accepted,
        UUID receiptId,
        UUID jobId,
        int attempt,
        WorkerResultRequest.CollectionOutcome collectionOutcome,
        Instant acceptedAt) {
}
