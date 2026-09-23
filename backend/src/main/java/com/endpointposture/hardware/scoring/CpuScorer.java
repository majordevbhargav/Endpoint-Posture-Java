package com.endpointposture.hardware.scoring;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Scores CPU health from {@code cpu_memory.cpu.LoadPercentage} — lower
 * sustained load scores higher. This is a load snapshot, not a defect
 * indicator; a CPU under heavy but legitimate load scores lower than one
 * that's idle, which is intentional (the score reflects headroom, not
 * hardware fault).
 */
@Component
public class CpuScorer implements ComponentScorer<Map<String, Object>> {

    @Override
    public Integer score(Map<String, Object> cpu) {
        if (cpu == null) return null;
        Object load = cpu.get("LoadPercentage");
        if (load == null) return null;
        double loadPct = toDouble(load);
        return clamp(100 - (int) Math.round(loadPct));
    }

    private double toDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString());
    }

    private int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }
}