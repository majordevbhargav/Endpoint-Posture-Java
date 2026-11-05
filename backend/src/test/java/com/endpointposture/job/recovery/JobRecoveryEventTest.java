package com.endpointposture.job.recovery;

import com.endpointposture.job.JobType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JobRecoveryEventTest {

    @Test
    void builderConstructsEventWithAllFields() {
        UUID id = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        Instant now = Instant.now();

        JobRecoveryEvent event = JobRecoveryEvent.builder()
                .id(id)
                .jobId(jobId)
                .endpointId(endpointId)
                .jobType(JobType.POSTURE_CHECK)
                .attemptCount(2)
                .claimedBy("worker:win-01")
                .recoveryType(RecoveryType.LEASE_EXPIRED)
                .reason("Lease expired after 300s")
                .recoveredAt(now)
                .build();

        assertEquals(id, event.getId());
        assertEquals(jobId, event.getJobId());
        assertEquals(endpointId, event.getEndpointId());
        assertEquals(JobType.POSTURE_CHECK, event.getJobType());
        assertEquals(2, event.getAttemptCount());
        assertEquals("worker:win-01", event.getClaimedBy());
        assertEquals(RecoveryType.LEASE_EXPIRED, event.getRecoveryType());
        assertEquals("Lease expired after 300s", event.getReason());
        assertEquals(now, event.getRecoveredAt());
    }

    @Test
    void recoveryTypeEnumValuesAreDefined() {
        assertNotNull(RecoveryType.valueOf("PROCESS_TIMEOUT"));
        assertNotNull(RecoveryType.valueOf("LEASE_EXPIRED"));
        assertNotNull(RecoveryType.valueOf("WORKER_REVOKED"));
        assertEquals(3, RecoveryType.values().length);
    }
}
