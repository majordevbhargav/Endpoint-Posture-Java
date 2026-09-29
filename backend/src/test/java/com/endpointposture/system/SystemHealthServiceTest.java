package com.endpointposture.system;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins down the status rules; they are pure functions, so no mocks are needed. */
class SystemHealthServiceTest {

    @Test
    void everythingHealthyHasNoWarningsAndIsUp() {
        List<String> w = SystemHealthService.warnings(true, true, 5L, true);
        assertTrue(w.isEmpty());
        assertEquals("UP", SystemHealthService.overallStatus(true, w));
    }

    @Test
    void emptyQueueIsNotAWarning() {
        assertTrue(SystemHealthService.warnings(true, true, null, true).isEmpty());
    }

    @Test
    void iseDownDegradesButDoesNotTakeTheSystemDown() {
        List<String> w = SystemHealthService.warnings(true, false, null, true);
        assertEquals(1, w.size());
        assertEquals("DEGRADED", SystemHealthService.overallStatus(true, w));
    }

    @Test
    void disabledWorkerPoolDegrades() {
        List<String> w = SystemHealthService.warnings(true, true, null, false);
        assertEquals("DEGRADED", SystemHealthService.overallStatus(true, w));
    }

    @Test
    void oldQueuedJobDegradesOnlyPastTheThreshold() {
        long threshold = SystemHealthService.STUCK_QUEUE_AFTER.getSeconds();
        assertTrue(SystemHealthService.warnings(true, true, threshold, true).isEmpty());
        assertEquals(1, SystemHealthService.warnings(true, true, threshold + 1, true).size());
    }

    @Test
    void databaseDownIsDownRegardlessOfOtherWarnings() {
        List<String> w = SystemHealthService.warnings(false, false, null, true);
        assertEquals("DOWN", SystemHealthService.overallStatus(false, w));
    }
}