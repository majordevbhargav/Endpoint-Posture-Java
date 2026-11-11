package com.endpointposture.workerruntime.api;

import java.time.Instant;
import java.util.UUID;

public record WorkerResultAcknowledgement(boolean accepted, UUID receiptId, UUID jobId, int attempt,
                                          String collectionOutcome, Instant acceptedAt) { }
