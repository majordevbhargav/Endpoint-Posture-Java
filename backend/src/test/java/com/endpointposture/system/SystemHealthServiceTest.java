package com.endpointposture.system;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins down the status rules; they are pure functions, so no mocks are needed. */
class SystemHealthServiceTest {

    @Test
    void everythingHealthyHasNoWarningsAndIsUp() {
        List<String> w = SystemHealthService.warnings(true, true, 5L, true, 3);
        assertTrue(w.isEmpty());
        assertEquals("UP", SystemHealthService.overallStatus(true, w));
    }

    @Test
    void emptyQueueIsNotAWarning() {
        assertTrue(SystemHealthService.warnings(true, true, null, true, 0).isEmpty());
    }

    @Test
    void iseDownDegradesButDoesNotTakeTheSystemDown() {
        List<String> w = SystemHealthService.warnings(true, false, null, true, 0);
        assertEquals(1, w.size());
        assertEquals("DEGRADED", SystemHealthService.overallStatus(true, w));
    }

    @Test
    void disabledLocalPoolWithEmptyQueueIsNotAWarning() {
        List<String> w = SystemHealthService.warnings(true, true, null, false, 0);
        assertTrue(w.isEmpty());
        assertEquals("UP", SystemHealthService.overallStatus(true, w));
    }

    @Test
    void disabledLocalPoolWithQueuedJobsDegrades() {
        List<String> w = SystemHealthService.warnings(true, true, 5L, false, 2);
        assertEquals(1, w.size());
        assertTrue(w.get(0).contains("2 queued"));
        assertEquals("DEGRADED", SystemHealthService.overallStatus(true, w));
    }

    @Test
    void enabledLocalPoolWithQueuedJobsIsNotDegradedByTheWorkerRule() {
        assertTrue(SystemHealthService.warnings(true, true, 5L, true, 50).isEmpty());
    }

    @Test
    void oldQueuedJobDegradesOnlyPastTheThreshold() {
        long threshold = SystemHealthService.STUCK_QUEUE_AFTER.getSeconds();
        assertTrue(SystemHealthService.warnings(true, true, threshold, true, 1).isEmpty());
        assertEquals(1, SystemHealthService.warnings(true, true, threshold + 1, true, 1).size());
    }

    @Test
    void databaseDownIsDownRegardlessOfOtherWarnings() {
        List<String> w = SystemHealthService.warnings(false, false, null, true, 0);
        assertEquals("DOWN", SystemHealthService.overallStatus(false, w));
    }
}