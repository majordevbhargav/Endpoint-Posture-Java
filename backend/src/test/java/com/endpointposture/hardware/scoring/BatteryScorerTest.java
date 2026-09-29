package com.endpointposture.hardware.scoring;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BatteryScorerTest {

    private final BatteryScorer scorer = new BatteryScorer();

    @Test
    void nullListReturnsNull_desktopHasNoBattery() {
        assertNull(scorer.score(null));
    }

    @Test
    void emptyListReturnsNull_desktopHasNoBattery() {
        assertNull(scorer.score(List.of()));
    }

    @Test
    void fullHealthBatteryScoresHundred() {
        List<Map<String, Object>> battery = List.of(
                Map.of("DesignedCapacity", 50000, "FullChargedCapacity", 50000));
        assertEquals(100, scorer.score(battery));
    }

    @Test
    void wornBatteryScoresProportionally() {
        List<Map<String, Object>> battery = List.of(
                Map.of("DesignedCapacity", 50000, "FullChargedCapacity", 40000));
        assertEquals(80, scorer.score(battery));
    }

    @Test
    void zeroDesignedCapacityEntryIsSkippedNotDivideByZero() {
        List<Map<String, Object>> battery = List.of(
                Map.of("DesignedCapacity", 0, "FullChargedCapacity", 100));
        assertNull(scorer.score(battery));
    }

    @Test
    void missingFullChargedCapacitySkipsThatEntry() {
        Map<String, Object> entry = new HashMap<>();
        entry.put("DesignedCapacity", 40000);
        entry.put("FullChargedCapacity", null);
        assertNull(scorer.score(List.of(entry)));
    }

    @Test
    void multipleBatteriesAreAggregatedNotAveragedPerBattery() {
        // (40000 + 20000) full / (40000 + 40000) design = 75%
        List<Map<String, Object>> battery = List.of(
                Map.of("DesignedCapacity", 40000, "FullChargedCapacity", 40000),
                Map.of("DesignedCapacity", 40000, "FullChargedCapacity", 20000));
        assertEquals(75, scorer.score(battery));
    }

    @Test
    void oneUsableEntryAmongUnusableOnesStillScores() {
        Map<String, Object> unusable = new HashMap<>();
        unusable.put("DesignedCapacity", 0);
        unusable.put("FullChargedCapacity", 100);
        List<Map<String, Object>> battery = List.of(
                unusable,
                Map.of("DesignedCapacity", 10000, "FullChargedCapacity", 9000));
        assertEquals(90, scorer.score(battery));
    }

    @Test
    void stringCapacityValuesAreParsed() {
        List<Map<String, Object>> battery = List.of(
                Map.of("DesignedCapacity", "50000", "FullChargedCapacity", "25000"));
        assertEquals(50, scorer.score(battery));
    }
}