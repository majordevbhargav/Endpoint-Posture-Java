package com.endpointposture.worker;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import static com.endpointposture.worker.WorkerRegistryService.WorkerState.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkerStateTest {
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final Duration STALE_AFTER = Duration.ofSeconds(90);

    @Test void revokedWinsOverRecentHeartbeat() {
        assertEquals(REVOKED, WorkerRegistryService.stateOf(false, NOW, NOW, STALE_AFTER));
        assertEquals(REVOKED, WorkerRegistryService.stateOf(false, null, NOW, STALE_AFTER));
    }
    @Test void neverSeenIsStale() {
        assertEquals(WorkerRegistryService.WorkerState.STALE, WorkerRegistryService.stateOf(true, null, NOW, STALE_AFTER));
    }
    @Test void thresholdBoundary() {
        assertEquals(ACTIVE, WorkerRegistryService.stateOf(true, NOW.minusSeconds(90), NOW, STALE_AFTER));
        assertEquals(WorkerRegistryService.WorkerState.STALE, WorkerRegistryService.stateOf(true, NOW.minusSeconds(91), NOW, STALE_AFTER));
        assertEquals(ACTIVE, WorkerRegistryService.stateOf(true, NOW, NOW, STALE_AFTER));
    }
    @Test void invalidThresholdsAreRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new WorkerRegistryService(null, 30, 59));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new WorkerRegistryService(null, 0, 90));
        new WorkerRegistryService(null, 30, 60);
    }
}


