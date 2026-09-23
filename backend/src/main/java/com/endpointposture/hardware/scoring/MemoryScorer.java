package com.endpointposture.hardware.scoring;

import org.springframework.stereotype.Component;

import java.util.Map;

/** Scores memory health from {@code cpu_memory.memory.UsedPercent}. */
@Component
public class MemoryScorer implements ComponentScorer<Map<String, Object>> {

    @Override
    public Integer score(Map<String, Object> memory) {
        if (memory == null) return null;
        Object used = memory.get("UsedPercent");
        if (used == null) return null;
        double usedPct = used instanceof Number n ? n.doubleValue() : Double.parseDouble(used.toString());
        return Math.max(0, Math.min(100, 100 - (int) Math.round(usedPct)));
    }
}