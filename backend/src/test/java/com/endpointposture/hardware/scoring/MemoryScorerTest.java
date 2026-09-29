package com.endpointposture.hardware.scoring;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MemoryScorerTest {

    private final MemoryScorer scorer = new MemoryScorer();

    @Test
    void nullSectionReturnsNull() {
        assertNull(scorer.score(null));
    }

    @Test
    void missingUsedPercentReturnsNull() {
        assertNull(scorer.score(Map.of()));
    }

    @Test
    void nullUsedPercentValueReturnsNull() {
        Map<String, Object> memory = new HashMap<>();
        memory.put("UsedPercent", null);
        assertNull(scorer.score(memory));
    }

    @Test
    void lowUsageScoresHigh() {
        assertEquals(90, scorer.score(Map.of("UsedPercent", 10.0)));
    }

    @Test
    void fullUsageScoresZero() {
        assertEquals(0, scorer.score(Map.of("UsedPercent", 100.0)));
    }

    @Test
    void overHundredPercentClampsToZero() {
        assertEquals(0, scorer.score(Map.of("UsedPercent", 120.0)));
    }

    @Test
    void roundsToNearestInt() {
        // 100 - round(33.4) = 100 - 33 = 67
        assertEquals(67, scorer.score(Map.of("UsedPercent", 33.4)));
    }

    @Test
    void stringValueIsParsed() {
        assertEquals(60, scorer.score(Map.of("UsedPercent", "40")));
    }
}