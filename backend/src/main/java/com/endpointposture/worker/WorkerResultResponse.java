package com.endpointposture.worker;

import java.time.Instant;
import java.util.UUID;

/**
 * Result receipt acknowledgement contract reserved for a later slice.
 * No controller returns this response in PR6.4 Slice 1.
 */
public record WorkerResultResponse(
        boolean accepted,
        UUID receiptId,
        UUID jobId,
        int attempt,
        WorkerResultRequest.CollectionOutcome collectionOutcome,
        Instant acceptedAt) {
}
