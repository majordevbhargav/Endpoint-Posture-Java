package com.endpointposture.job.recovery;

import com.endpointposture.job.JobType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent, append-only record of a job attempt recovered from a crash, timeout, or expired lease.
 *
 * <p>Unique on (job_id, attempt_count) to enforce recovery idempotency at the database level.</p>
 */
@Entity
@Table(name = "job_recovery_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobRecoveryEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "endpoint_id", nullable = false)
    private UUID endpointId;

    @Column(name = "endpoint_mac_address", nullable = false)
    private String endpointMacAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false)
    private JobType jobType;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "claimed_by")
    private String claimedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "recovery_type", nullable = false)
    private RecoveryType recoveryType;

    @Column(nullable = false)
    private String reason;

    @Column(name = "recovered_at", nullable = false)
    private Instant recoveredAt;
}

