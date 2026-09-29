package com.endpointposture.hardware.scoring;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CpuScorerTest {

    private final CpuScorer scorer = new CpuScorer();

    @Test
    void nullSectionReturnsNull() {
        assertNull(scorer.score(null));
    }

    @Test
    void missingLoadPercentageReturnsNull() {
        assertNull(scorer.score(Map.of()));
    }

    @Test
    void nullLoadPercentageValueReturnsNull() {
        Map<String, Object> cpu = new HashMap<>();
        cpu.put("LoadPercentage", null);
        assertNull(scorer.score(cpu));
    }

    @Test
    void zeroLoadScoresMax() {
        assertEquals(100, scorer.score(Map.of("LoadPercentage", 0)));
    }

    @Test
    void fullLoadScoresZero() {
        assertEquals(0, scorer.score(Map.of("LoadPercentage", 100)));
    }

    @Test
    void midLoadScoresTheComplement() {
        assertEquals(65, scorer.score(Map.of("LoadPercentage", 35)));
    }

    @Test
    void loadAboveHundredClampsToZero() {
        // Should never happen from a real agent, but the scorer must not go negative.
        assertEquals(0, scorer.score(Map.of("LoadPercentage", 150)));
    }

    @Test
    void stringLoadValueIsParsed() {
        assertEquals(70, scorer.score(Map.of("LoadPercentage", "30")));
    }

    @Test
    void doubleLoadValueIsRounded() {
        // 100 - round(24.6) = 100 - 25 = 75
        assertEquals(75, scorer.score(Map.of("LoadPercentage", 24.6)));
    }
}