package com.endpointposture.worker.result;

import com.endpointposture.worker.WorkerResultRequest.CollectionOutcome;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable, append-only acknowledgement record for a worker result attempt.
 *
 * <p>jobId and workerId are intentionally scalar historical identifiers, not
 * entity relationships. Receipts must remain readable after job or worker
 * registry retention/deletion.</p>
 */
@Entity
@Table(name = "worker_job_result_receipt",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_worker_job_result_receipt_job_attempt",
                columnNames = {"job_id", "attempt"}),
        indexes = @Index(name = "idx_worker_job_result_receipt_worker_accepted",
                columnList = "worker_id, accepted_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkerJobResultReceipt {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Column(name = "attempt", nullable = false, updatable = false)
    private int attempt;

    @Column(name = "worker_id", nullable = false, updatable = false)
    private UUID workerId;

    @Column(name = "result_hash", nullable = false, length = 64, updatable = false)
    private String resultHash;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "collection_outcome", nullable = false, length = 16, updatable = false)
    private CollectionOutcome collectionOutcome;

    @Column(name = "accepted_at", nullable = false, updatable = false)
    private Instant acceptedAt;

    @PrePersist
    void onCreate() {
        if (acceptedAt == null) acceptedAt = Instant.now();
    }
}
