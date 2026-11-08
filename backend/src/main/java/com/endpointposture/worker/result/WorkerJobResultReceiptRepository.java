package com.endpointposture.worker.result;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Data access for durable worker result receipts. */
public interface WorkerJobResultReceiptRepository
        extends JpaRepository<WorkerJobResultReceipt, UUID> {

    Optional<WorkerJobResultReceipt> findByJobIdAndAttempt(UUID jobId, int attempt);
}
