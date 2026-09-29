package com.endpointposture.hardware.scoring;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StorageScorerTest {

    private final StorageScorer scorer = new StorageScorer();

    @Test
    void nullDiskListReturnsNull() {
        assertNull(scorer.score(null));
    }

    @Test
    void emptyDiskListReturnsNull() {
        assertNull(scorer.score(List.of()));
    }

    @Test
    void allDisksHealthyByNameScoresHundred() {
        List<Map<String, Object>> disks = List.of(
                Map.of("HealthStatus", "Healthy"),
                Map.of("HealthStatus", "Healthy"));
        assertEquals(100, scorer.score(disks));
    }

    @Test
    void healthStatusZeroCodeCountsAsHealthy() {
        // PowerShell's numeric health-status code for "healthy" is 0.
        List<Map<String, Object>> disks = List.of(Map.of("HealthStatus", "0"));
        assertEquals(100, scorer.score(disks));
    }

    @Test
    void healthStatusMatchingIsCaseAndWhitespaceInsensitive() {
        List<Map<String, Object>> disks = List.of(Map.of("HealthStatus", "  HEALTHY  "));
        assertEquals(100, scorer.score(disks));
    }

    @Test
    void oneUnhealthyDiskOfTwoScoresFifty() {
        List<Map<String, Object>> disks = List.of(
                Map.of("HealthStatus", "Healthy"),
                Map.of("HealthStatus", "Warning"));
        assertEquals(50, scorer.score(disks));
    }

    @Test
    void allUnhealthyScoresZero() {
        List<Map<String, Object>> disks = List.of(Map.of("HealthStatus", "Unhealthy"));
        assertEquals(0, scorer.score(disks));
    }

    @Test
    void missingHealthStatusFieldCountsAsUnhealthy() {
        Map<String, Object> disk = new HashMap<>();
        disk.put("HealthStatus", null);
        assertEquals(0, scorer.score(List.of(disk)));
    }

    @Test
    void unevenSplitRoundsToNearestInt() {
        // 1 of 3 healthy = 33.33% -> rounds to 33
        List<Map<String, Object>> disks = List.of(
                Map.of("HealthStatus", "Healthy"),
                Map.of("HealthStatus", "Warning"),
                Map.of("HealthStatus", "Warning"));
        assertEquals(33, scorer.score(disks));
    }
}