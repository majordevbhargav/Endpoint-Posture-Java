package com.endpointposture.job.recovery;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link JobRecoveryEvent}.
 */
public interface JobRecoveryEventRepository extends JpaRepository<JobRecoveryEvent, UUID> {

    Optional<JobRecoveryEvent> findByJobIdAndAttemptCount(UUID jobId, int attemptCount);

    List<JobRecoveryEvent> findByEndpointIdOrderByRecoveredAtDesc(UUID endpointId, Pageable pageable);

    List<JobRecoveryEvent> findByJobIdOrderByAttemptCountAsc(UUID jobId);
}
